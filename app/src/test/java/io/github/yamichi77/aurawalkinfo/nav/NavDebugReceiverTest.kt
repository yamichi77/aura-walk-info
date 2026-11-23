package io.github.yamichi77.aurawalkinfo.nav

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.AppContainer
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.REPLAY_ACTION
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.ReplayController
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import io.github.yamichi77.aurawalkinfo.location.LocationRepository
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.WalkSessionInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NavDebugReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun registersOnlyInDebugBuildsAndRequiresDumpPermission() {
        val info = app.applicationInfo
        val flags = info.flags
        try {
            info.flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            AppContainer(app)
            assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(NAV_ACTION) })
            info.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            AppContainer(app)
            val receiver = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(NAV_ACTION) }
            assertEquals(Manifest.permission.DUMP, receiver.broadcastPermission)
        } finally {
            info.flags = flags
        }
    }

    @Test
    fun syntheticCommandUsesSharedReplayAndReachesArrivalWithoutRealLocationOrHttp() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val live = object : LocationRepository {
            override val locations = flow<LocationSample> { error("Synthetic mode requested a real fix") }
        }
        val replay = ReplayController(app, clock, WalkSessionInput(live, clock, { emptyFlow() }), backgroundScope)
        val nav = NavRepository(backgroundScope, object : NavService {
            override suspend fun search(query: String, origin: NavPoint): List<NavCandidate> = error("Unexpected Places call")
            override suspend fun route(origin: NavPoint, destination: NavPoint): NavRoute = error("Unexpected Routes call")
        }, live.locations, clock)
        registerNavDebugReceiver(app, nav, replay.navigationLocations)

        val walkSamples = mutableListOf<LocationSample>()
        val replayCommand = Intent(REPLAY_ACTION).putExtra("scenario", "navigation").putExtra("speed", 4f)
        dispatch(replayCommand)
        backgroundScope.launch {
            val input = replay.inputs.first()
            input.locations.locations.collect(walkSamples::add)
        }
        runCurrent()
        assertEquals(NavPoint(0.0, 0.0), walkSamples.single().navPoint())
        advanceTimeBy(2_500)
        runCurrent()

        dispatch(Intent(NAV_ACTION).putExtra("scenario", "synthetic"))
        runCurrent()
        val guiding = nav.state.value as NavState.Guiding
        assertEquals(NavDirection.RIGHT, guiding.guidance.nextTurn?.step?.direction)
        assertEquals(walkSamples.last().latitude, guiding.guidance.positionOnRoute.latitude, 1e-12)

        advanceTimeBy(5_250)
        runCurrent()
        assertEquals(90f, walkSamples.last().bearingDeg)
        assertNull((nav.state.value as NavState.Guiding).guidance.nextTurn)
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(nav.state.value is NavState.Arrived)
        dispatch(Intent(NAV_ACTION).putExtra("action", "cancel"))
        runCurrent()
        assertEquals(NavState.Idle, nav.state.value)
    }

    @Test
    fun dispatchesQueryAndDirectCoordinatesToTheFakeService() = runTest {
        val route = syntheticNavRoute()
        val destination = NavPoint(0.001f.toDouble(), 0.002f.toDouble())
        val candidate = NavCandidate("sample", "合成の施設", "架空市", destination, 248.0)
        val service = object : NavService {
            override suspend fun search(query: String, origin: NavPoint): List<NavCandidate> {
                assertEquals("合成の施設", query)
                assertEquals(route.polyline.first(), origin)
                return listOf(candidate)
            }

            override suspend fun route(origin: NavPoint, destination: NavPoint): NavRoute {
                assertEquals(candidate.point, destination)
                assertEquals(route.polyline.first(), origin)
                return route
            }
        }
        val clock = Clock { testScheduler.currentTime }
        val nav = NavRepository(backgroundScope, service, flowOf(syntheticFix(0)), clock)
        registerNavDebugReceiver(app, nav, emptyFlow())

        dispatch(Intent(NAV_ACTION).putExtra("query", "合成の施設"))
        runCurrent()
        assertEquals(NavState.Candidates(listOf(candidate)), nav.state.value)

        dispatch(Intent(NAV_ACTION).putExtra("lat", 0.001f).putExtra("lng", 0.002f))
        runCurrent()
        assertEquals(NavDestination(destination), (nav.state.value as NavState.Guiding).destination)
    }

    private fun dispatch(intent: Intent) {
        val receiver = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(intent.action) }
        receiver.broadcastReceiver.onReceive(app, intent)
    }
}
