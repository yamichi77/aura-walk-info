package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.ReplayScenario
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.haversine
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.replayInput
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalkSessionControlTest {
    @Test
    fun countsDownCancelsMeasuresSyntheticRouteAndKeepsSummaryUntilNextWalk() = runTest {
        val recordings = mutableListOf<List<SessionMetrics>>()
        val completed = mutableListOf<Int>()
        val input = replayInput(ReplayScenario.WALK_STOP, 4f, Clock { testScheduler.currentTime }) { rows ->
            val recording = mutableListOf<SessionMetrics>()
            val index = recordings.size
            recordings.add(recording)
            rows.collect { recording.add(it) }
            completed.add(index)
        }
        val repository = WalkSessionRepository(flowOf(input), haversine)
        repository.start(backgroundScope, SessionStartMode.STANDBY)
        repository.configure(SessionStartMode.STANDBY, legacyReplay = false)
        runCurrent()
        var phase: WalkPhase = WalkPhase.Standby

        fun state() = repository.state.value
        fun tap() {
            phase = phase.tap(testScheduler.currentTime, state().motionState)
        }
        fun advance(millis: Long) {
            advanceTimeBy(millis)
            runCurrent()
            val previous = phase
            phase = phase.advance(testScheduler.currentTime)
            if (previous is WalkPhase.Starting && phase == WalkPhase.Measuring) repository.setMeasuring(true)
            if (previous is WalkPhase.Ending && phase == WalkPhase.Summary) repository.setMeasuring(false)
            runCurrent()
        }

        advance(1_000)
        assertEquals(MotionState.WALKING, state().motionState)
        assertNotNull(state().mapCenter)
        assertEquals(0L, state().elapsedMillis)
        assertEquals(0.0, state().distanceMeters, 0.0)
        assertTrue(recordings.isEmpty())
        tap()
        assertEquals(3, phase.remainingSeconds(testScheduler.currentTime))
        advance(1_000)
        assertEquals(2, phase.remainingSeconds(testScheduler.currentTime))
        tap()
        assertEquals(WalkPhase.Standby, phase)
        assertTrue(recordings.isEmpty())
        tap()
        advance(2_999)
        assertEquals(1, phase.remainingSeconds(testScheduler.currentTime))
        advance(1)
        assertEquals(WalkPhase.Measuring, phase)
        assertEquals(0L, state().elapsedMillis)
        assertEquals(0.0, state().distanceMeters, 0.0)
        advance(5_000)
        assertTrue(state().distanceMeters > 0)
        assertTrue(state().walkingTimeMillis > 0)
        tap()
        advance(1_000)
        tap()
        assertEquals(WalkPhase.Measuring, phase)
        val beforeEnd = state()
        tap()
        advance(3_000)
        assertEquals(WalkPhase.Summary, phase)
        val summary = state()
        assertTrue(summary.walkingTimeMillis > beforeEnd.walkingTimeMillis)
        assertEquals(listOf(0), completed)
        assertTrue(recordings.single().any { it.distanceMeters > 0 })
        advance(5_000)
        assertEquals(summary.distanceMeters, state().distanceMeters, 0.0)
        assertEquals(summary.elapsedMillis, state().elapsedMillis)
        assertEquals(summary.walkingTimeMillis, state().walkingTimeMillis)
        assertEquals(1, recordings.size)
        tap()
        advance(3_000)
        assertEquals(WalkPhase.Measuring, phase)
        assertEquals(0L, state().elapsedMillis)
        assertEquals(0.0, state().distanceMeters, 0.0)
        assertEquals(2, recordings.size)
    }

    @Test
    fun fastTapsAreIgnoredWhileAnExistingCountdownAdvances() {
        val stopped = MotionState.STOPPED
        val fast = MotionState.FAST
        assertEquals(WalkPhase.Standby, WalkPhase.Standby.tap(0, fast))
        val starting = WalkPhase.Standby.tap(0, stopped)
        assertEquals(starting, starting.tap(1_000, fast))
        assertEquals(WalkPhase.Measuring, starting.tap(3_000, fast))
        val ending = WalkPhase.Measuring.tap(4_000, stopped)
        assertEquals(ending, ending.tap(6_000, fast))
        assertEquals(WalkPhase.Summary, ending.tap(7_000, fast))
    }
}
