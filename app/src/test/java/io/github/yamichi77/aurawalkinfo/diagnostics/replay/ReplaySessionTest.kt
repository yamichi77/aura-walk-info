package io.github.yamichi77.aurawalkinfo.diagnostics.replay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.location.AddressParts
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.HudVisibility
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.SessionMetrics
import io.github.yamichi77.aurawalkinfo.session.WalkSessionInput
import io.github.yamichi77.aurawalkinfo.session.WalkSessionRepository
import io.github.yamichi77.aurawalkinfo.ui.hud.HudVisualSpec
import io.github.yamichi77.aurawalkinfo.ui.hud.hudVisualSpecFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaySessionTest {
    @Test
    fun fastFreezesTotalsUntilWalkingResumes() = runTest {
        val rows = replay(ReplayScenario.FAST)
        val fast = rows.first { it.motion == MotionState.FAST }.elapsedSeconds.toInt()
        val walking = (fast + 1..89).first { rows[it].motion == MotionState.WALKING }
        assertTrue("FAST at ${fast}s", fast - 30 in 5..9)
        assertTrue("WALKING at ${walking}s", walking - 60 in 5..9)
        assertFrozen(rows, fast..walking - 1, MotionState.FAST)
        assertTrue(rows[89].walkingTimeMillis > rows[walking].walkingTimeMillis)
    }

    @Test
    fun stopFreezesTotalsAndMapTurnsNinetyDegreesAfterWalkingResumes() = runTest {
        val rows = replay(ReplayScenario.WALK_STOP)
        val stopped = rows.first { it.motion == MotionState.STOPPED }.elapsedSeconds.toInt()
        val walking = (stopped + 1..149).first { rows[it].motion == MotionState.WALKING }
        assertTrue("STOPPED at ${stopped}s", stopped - 60 in 5..9)
        assertTrue(walking in 90..92)
        assertFrozen(rows, stopped..walking - 1, MotionState.STOPPED)
        assertEquals(90f, checkNotNull(rows[120].bearingDeg) - checkNotNull(rows[119].bearingDeg), 2f)
    }

    @Test
    fun noFixTimesOutAndPoorAccuracyKeepsBothTotalsFrozen() = runTest {
        val rows = replay(ReplayScenario.NO_FIX)
        val lost = (30..44).first { rows[it].motion == MotionState.NO_FIX }
        assertTrue(lost - 29 in 9..11)
        assertFrozen(rows, lost..54, MotionState.NO_FIX)
        assertTrue((45..54).all { rows[it].accuracyMeters == 40f })
        assertEquals(MotionState.WALKING, rows[55].motion)
    }

    @Test
    fun switchingAndRestartingReplayResetsTheSessionAndIsolatesRealLoggingAndAddress() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val realLocations = FakeLocationRepository()
        var realAddressLookups = 0
        val realRows = mutableListOf<SessionMetrics>()
        val real = WalkSessionInput(
            realLocations, clock, { fixes ->
                flow {
                    fixes.collect {
                        realAddressLookups++
                        emit(AddressState.Failed)
                    }
                }
            },
            record = { it.collect(realRows::add) },
        )
        val inputs = MutableStateFlow(real)
        val repo = WalkSessionRepository(inputs, haversine)
        repo.start(backgroundScope)
        runCurrent()
        realLocations.emit(syntheticFix(0))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2_000L, repo.state.value.walkingTimeMillis)
        val beforeReplay = realRows.size
        assertEquals(1, realAddressLookups)

        repeat(2) {
            inputs.value = replayInput(ReplayScenario.FAST, 4f, clock) { it.collect {} }
            runCurrent()
            assertEquals(0L, repo.state.value.elapsedMillis)
            assertEquals(0L, repo.state.value.walkingTimeMillis)
            assertEquals(0.0, repo.state.value.distanceMeters, 0.0)
            assertEquals(
                AddressState.Available(AddressParts("東京都", "千代田区", "丸の内", "1丁目")),
                repo.state.value.address,
            )
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(8_000L, repo.state.value.walkingTimeMillis)
            assertEquals(beforeReplay, realRows.size)
            assertEquals(1, realAddressLookups)
        }

        inputs.value = real
        runCurrent()
        assertEquals(MotionState.NO_FIX, repo.state.value.motionState)
        assertEquals(AddressState.Loading, repo.state.value.address)
        realLocations.emit(syntheticFix(testScheduler.currentTime))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, realAddressLookups)
        assertEquals(beforeReplay + 1, realRows.size)
        assertEquals(1L, realRows.last().elapsedSeconds)
        assertEquals(1_000L, repo.state.value.walkingTimeMillis)
    }

    private fun TestScope.replay(scenario: ReplayScenario): List<SessionMetrics> {
        val rows = mutableListOf<SessionMetrics>()
        val clock = Clock { testScheduler.currentTime }
        val input = replayInput(scenario, 4f, clock) { it.collect(rows::add) }
        val repository = WalkSessionRepository(flowOf(input), haversine)
        repository.start(backgroundScope)
        advanceTimeBy(scenario.durationSeconds * 250L)
        runCurrent()
        assertEquals((0L..scenario.durationSeconds.toLong()).toList(), rows.map { it.elapsedSeconds })
        assertEquals(scenario.durationSeconds * 1_000L, repository.state.value.elapsedMillis)
        return rows
    }

    private fun assertFrozen(rows: List<SessionMetrics>, seconds: IntRange, motion: MotionState) {
        val first = rows[seconds.first]
        for (second in seconds) {
            assertEquals("motion at ${second}s", motion, rows[second].motion)
            assertEquals("distance at ${second}s", first.distanceMeters, rows[second].distanceMeters, 0.0)
            assertEquals("walking time at ${second}s", first.walkingTimeMillis, rows[second].walkingTimeMillis)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReplayHudTest {
    @Test
    fun fastReplayOverridesNoticeAndManualVisibilityUntilWalkingResumes() = runTest {
        val notice = SafetyNotice(ApplicationProvider.getApplicationContext<Context>())
        val pending = HudVisibility(notice, showNotice = true)
        val normal = HudVisibility(notice, showNotice = false)
        val hidden = HudVisibility(notice, showNotice = false)
        val input = replayInput(ReplayScenario.FAST, 4f, Clock { testScheduler.currentTime }) { it.collect {} }
        val repo = WalkSessionRepository(flowOf(input), haversine)
        repo.start(backgroundScope)
        fun motion() = repo.state.value.motionState
        fun spec(visibility: HudVisibility) =
            hudVisualSpecFor(repo.state.value, visibility.hidden, visibility.noticePending)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(MotionState.WALKING, motion())
        assertEquals("hud-hidden", hidden.activate(motion(), 5_000))
        assertEquals(HudVisualSpec.Hidden, spec(hidden))
        assertTrue((spec(pending) as HudVisualSpec.Clusters).showSafetyNotice)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(MotionState.FAST, motion())
        for (visibility in listOf(pending, normal, hidden)) {
            assertEquals("ignored-fast", visibility.activate(motion(), 10_000))
            assertEquals(HudVisualSpec.Fast, spec(visibility))
        }
        assertEquals("高速移動中のため非表示", HudVisualSpec.Fast.message)

        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(MotionState.WALKING, motion())
        assertTrue(spec(normal) is HudVisualSpec.Clusters)
        assertEquals(HudVisualSpec.Hidden, spec(hidden))
        assertTrue((spec(pending) as HudVisualSpec.Clusters).showSafetyNotice)
        assertEquals("notice-dismissed", pending.activate(motion(), 18_000))
        assertEquals("debounced", pending.activate(motion(), 18_499))
        assertEquals("hud-hidden", pending.activate(motion(), 18_500))
    }
}
