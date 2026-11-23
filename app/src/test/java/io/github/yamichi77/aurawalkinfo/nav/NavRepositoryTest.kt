package io.github.yamichi77.aurawalkinfo.nav

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import io.github.yamichi77.aurawalkinfo.session.Clock
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavRepositoryTest {
    @Test
    fun searchesShowsAtMostThreeCandidatesAndRoutesOnlyASelectedCandidate() = runTest {
        val fixture = Fixture(this)
        val nav = fixture.nav
        nav.search("  合成  ")
        runCurrent()
        assertEquals(NavState.Searching, nav.state.value)
        fixture.fix()
        assertEquals("合成", fixture.service.queries.single())
        val candidates = (nav.state.value as NavState.Candidates).items
        assertEquals(fixture.service.candidates.take(3), candidates)
        nav.selectCandidate(fixture.service.candidates.last())
        runCurrent()
        assertTrue(fixture.service.origins.isEmpty())

        nav.selectCandidate(candidates.first())
        runCurrent()
        val destination = NavDestination(candidates.first().point, candidates.first().name)
        assertEquals(NavState.Routing(destination), nav.state.value)
        fixture.fix()
        assertEquals(destination, (nav.state.value as NavState.Guiding).destination)
        assertEquals(listOf(fixture.start), fixture.service.origins)
        nav.cancel()
        runCurrent()
        assertEquals(NavState.Idle, nav.state.value)
    }

    @Test
    fun staleAndInaccurateFixesHoldGuidanceAndInterruptOffRouteEvidence() = runTest {
        val fixture = Fixture(this)
        fixture.startNav()
        fixture.fix(after = 1_000, east = 31.0, accuracy = 30f) // Within max(25m, 2 × accuracy).
        fixture.fix(after = 8_000, east = 31.0, accuracy = 30f)
        assertEquals(1, fixture.service.origins.size)
        fixture.fix(after = 1_000, east = 31.0)
        val lastGood = fixture.nav.state.value
        fixture.fix(after = 7_999, east = 80.0, accuracy = 31f)
        assertEquals(lastGood, fixture.nav.state.value)
        fixture.fix(after = 1, east = 31.0)
        advanceTimeBy(10_000) // No updates for the entire fix timeout.
        fixture.fix(east = 31.0)
        fixture.fix(after = 7_999, east = 31.0)
        assertEquals(1, fixture.service.origins.size)
        fixture.fix(after = 1, east = 31.0)
        assertEquals(2, fixture.service.origins.size)
    }

    @Test
    fun appliesReplacementRouteOnlyWithAFreshFixAndDiscardsCancelledResponses() = runTest {
        val fixture = Fixture(this)
        fixture.startNav()
        val initial = fixture.service.route
        val replacement = initial.copy(distanceMeters = 250)
        val response = CompletableDeferred<NavRoute>()
        fixture.service.response = response
        fixture.fix(after = 1_000, east = 40.0)
        fixture.fix(after = 8_000, east = 40.0)
        val previous = (fixture.nav.state.value as NavState.Rerouting).previous
        fixture.fix(after = 10_000, east = 45.0, accuracy = 31f)
        response.complete(replacement)
        runCurrent()
        assertEquals(previous, fixture.nav.state.value)
        fixture.fix(after = 1, east = 40.0)
        assertEquals(replacement, (fixture.nav.state.value as NavState.Guiding).route)

        fixture.service.response = CompletableDeferred()
        fixture.nav.navigateTo(fixture.end)
        runCurrent()
        fixture.fix(after = 1_000)
        fixture.nav.cancel()
        runCurrent()
        fixture.service.response?.complete(replacement)
        runCurrent()
        assertEquals(NavState.Idle, fixture.nav.state.value)
    }

    @Test
    fun checksStrictOffRouteDistanceEightSecondConfirmationThirtySecondIntervalAndTwentyAttemptCap() {
        val tracker = RerouteTracker()
        assertFalse(tracker.shouldReroute(0, 0, 25.0, 5f))
        assertFalse(tracker.shouldReroute(1, 1, 26.0, 5f))
        assertFalse(tracker.shouldReroute(8_000, 8_000, 26.0, 5f))
        assertTrue(tracker.shouldReroute(8_001, 8_001, 26.0, 5f))
        assertFalse(tracker.shouldReroute(16_001, 16_001, 26.0, 5f))
        assertFalse(tracker.shouldReroute(38_000, 38_000, 26.0, 5f))
        assertTrue(tracker.shouldReroute(38_001, 38_001, 26.0, 5f))
        for (attempt in 3..20) {
            val time = 8_001 + (attempt - 1) * 30_000L
            assertFalse(tracker.shouldReroute(time - 8_000, time - 8_000, 26.0, 5f))
            assertTrue(tracker.shouldReroute(time, time, 26.0, 5f))
        }
        assertFalse(tracker.shouldReroute(600_001, 600_001, 26.0, 5f))
        assertFalse(tracker.shouldReroute(608_001, 608_001, 26.0, 5f))
    }

    @Test
    fun arrivalRequiresTwentyMetersAndTwentyFiveMeterAccuracyAndLastsTenSeconds() = runTest {
        val fixture = Fixture(this)
        fixture.startNav()
        fixture.fix(after = 1_000, north = 20.01, accuracy = 25f)
        assertTrue(fixture.nav.state.value is NavState.Guiding)
        fixture.fix(after = 1_000, north = 19.0, accuracy = 25.01f)
        val latest = fixture.nav.state.value
        assertTrue(latest is NavState.Guiding)
        fixture.fix(after = 1_000, north = 0.0, accuracy = 31f)
        assertEquals(latest, fixture.nav.state.value)
        fixture.fix(after = 1_000, north = 0.0, timestamp = 0L)
        assertEquals(latest, fixture.nav.state.value)
        fixture.fix(after = 1_000, north = 20.0, accuracy = 25f)
        assertEquals(NavState.Arrived(NavDestination(fixture.end)), fixture.nav.state.value)
        advanceTimeBy(9_999)
        assertTrue(fixture.nav.state.value is NavState.Arrived)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(NavState.Idle, fixture.nav.state.value)
    }

    @Test
    fun noFreshOriginTimesOutWithoutCallingSearchOrRoute() = runTest {
        val fixture = Fixture(this)
        fixture.nav.navigateTo(fixture.end)
        runCurrent()
        fixture.fix(after = 1_000, accuracy = 31f)
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(NavState.Error(NavError.NO_FIX), fixture.nav.state.value)
        assertTrue(fixture.service.origins.isEmpty())
    }

    private class Fixture(private val scope: TestScope) {
        val start = syntheticFix(0, northMeters = 200.0).navPoint()
        val end = NavPoint(0.0, 0.0)
        val service = FakeNavService(NavRoute(200, Duration.ofSeconds(140), listOf(start, end), emptyList()), end)
        private val fixes = MutableSharedFlow<LocationSample>(extraBufferCapacity = 1)
        private val clock = Clock { scope.testScheduler.currentTime }
        val nav = NavRepository(scope.backgroundScope, service, fixes, clock)

        fun startNav() {
            nav.navigateTo(end)
            scope.runCurrent()
            fix()
            assertTrue(nav.state.value is NavState.Guiding)
        }

        fun fix(after: Long = 0, north: Double = 200.0, east: Double = 0.0, accuracy: Float = 5f, timestamp: Long? = null) {
            if (after > 0) scope.advanceTimeBy(after)
            assertTrue(fixes.tryEmit(syntheticFix(timestamp ?: clock.elapsedRealtimeMillis(), north, accuracy, eastMeters = east)))
            scope.runCurrent()
        }
    }

    private class FakeNavService(val route: NavRoute, end: NavPoint) : NavService {
        val origins = mutableListOf<NavPoint>()
        val queries = mutableListOf<String>()
        val candidates = List(5) { NavCandidate("id$it", "施設$it", "架空市", end, 200.0) }
        var response: CompletableDeferred<NavRoute>? = null

        override suspend fun search(query: String, origin: NavPoint): List<NavCandidate> {
            queries += query
            return candidates
        }

        override suspend fun route(origin: NavPoint, destination: NavPoint): NavRoute {
            origins += origin
            return response?.await() ?: route
        }
    }
}
