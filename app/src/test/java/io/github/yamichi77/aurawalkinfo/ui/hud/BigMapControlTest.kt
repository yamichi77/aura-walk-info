package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.ReplayScenario
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.haversine
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.replayInput
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.SessionStartMode
import io.github.yamichi77.aurawalkinfo.session.WalkSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BigMapControlTest {
    private val closed = BigMapControl()

    @Test
    fun timesOutAfterThirtySecondsStoppedOrTenWalkingOrWithoutFix() {
        MotionState.entries.filter { it != MotionState.FAST }.forEach { motion ->
            val deadline = if (motion == MotionState.STOPPED) 30_000L else 10_000L
            val opened = closed.tap(HudTarget.MAP, 0, motion)
            assertEquals(deadline, opened.closesAt)
            assertTrue(opened.advance(deadline - 1, motion).isOpen)
            assertFalse(opened.advance(deadline, motion).isOpen)
        }
    }

    @Test
    fun walkingOrLosingFixOnlyShortensTheDeadline() {
        val stopped = MotionState.STOPPED
        val opened = closed.tap(HudTarget.MAP, 0, stopped)
        val walking = opened.advance(5_000, MotionState.WALKING)
        assertEquals(15_000L, walking.closesAt)
        assertEquals(walking, walking.advance(6_000, stopped))
        assertEquals(walking, opened.advance(5_000, MotionState.NO_FIX))
        assertEquals(opened, opened.advance(25_000, MotionState.WALKING))
    }

    @Test
    fun fastClosesImmediatelyAndRefusesToOpenAndBothTargetsCloseManually() {
        val fast = MotionState.FAST
        val stopped = MotionState.STOPPED
        val opened = closed.tap(HudTarget.MAP, 0, stopped)
        assertEquals(closed, opened.advance(1, fast))
        assertEquals(closed, closed.tap(HudTarget.MAP, 1, fast))
        assertEquals(closed, opened.tap(HudTarget.BIGMAP, 1, stopped))
        assertEquals(closed, opened.tap(HudTarget.OUTSIDE, 1, stopped))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun followsWalkingStoppedAndFastReplayWithVirtualTime() = runTest {
        fun input(scenario: ReplayScenario) = replayInput(scenario, 1f, Clock { testScheduler.currentTime }) {}
        val inputs = MutableStateFlow(input(ReplayScenario.WALK_STOP))
        val repository = WalkSessionRepository(inputs, haversine)
        repository.start(backgroundScope, SessionStartMode.STANDBY)
        repository.configure(SessionStartMode.STANDBY, legacyReplay = false)
        var map = BigMapControl()
        fun now() = testScheduler.currentTime
        fun motion() = repository.state.value.motionState
        backgroundScope.launch {
            repository.state.collect { map = map.advance(now(), it.motionState) }
        }
        fun advance(millis: Long) {
            advanceTimeBy(millis)
            runCurrent()
            map = map.advance(now(), motion())
        }
        fun open() {
            map = map.tap(HudTarget.MAP, now(), motion())
        }

        runCurrent()
        assertEquals(MotionState.WALKING, motion())
        open()
        advance(9_999)
        assertTrue(map.isOpen)
        advance(1)
        assertFalse(map.isOpen)

        advance(59_000)
        assertEquals(MotionState.STOPPED, motion())
        open()
        assertEquals(now() + 30_000, map.closesAt)
        advance(29_999)
        assertTrue(map.isOpen)
        advance(1)
        assertFalse(map.isOpen)

        inputs.value = input(ReplayScenario.FAST)
        runCurrent()
        advance(30_000)
        assertEquals(MotionState.WALKING, motion())
        open()
        advance(6_000)
        assertTrue(map.isOpen)
        advance(1_000)
        assertEquals(MotionState.FAST, motion())
        assertFalse(map.isOpen)
        open()
        assertFalse(map.isOpen)
    }
}
