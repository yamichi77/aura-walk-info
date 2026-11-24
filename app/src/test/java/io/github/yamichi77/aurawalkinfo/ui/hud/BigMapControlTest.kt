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
    fun startingAndFinishingInputWhileWalkingOrWithoutFixNeverExtendTheDeadline() {
        for (motion in listOf(MotionState.WALKING, MotionState.NO_FIX)) {
            val opened = closed.tap(HudTarget.MAP, 0, motion)
            val entering = opened.advance(4_000, motion, destinationInput = true)
            assertEquals(10_000L, entering.closesAt)
            assertTrue(entering.advance(9_999, motion, destinationInput = true).isOpen)
            assertFalse(entering.advance(10_000, motion, destinationInput = true).isOpen)
            assertEquals(10_000L, entering.advance(7_000, motion).closesAt)
        }
    }

    @Test
    fun stoppedInputPausesAndFinishingWhileStoppedStartsAFullThirtySeconds() {
        val stopped = MotionState.STOPPED
        val paused = closed.tap(HudTarget.MAP, 0, stopped).advance(1_000, stopped, destinationInput = true)
        assertTrue(paused.isOpen)
        assertEquals(null, paused.closesAt)
        assertEquals(paused, paused.advance(40_000, stopped, destinationInput = true))
        assertEquals(70_000L, paused.advance(40_000, stopped).closesAt)
    }

    @Test
    fun movingDuringPausedInputStartsTenSecondsAndFastClosesImmediately() {
        val stopped = MotionState.STOPPED
        val paused = closed.tap(HudTarget.MAP, 0, stopped).advance(1_000, stopped, destinationInput = true)
        for (motion in listOf(MotionState.WALKING, MotionState.NO_FIX)) {
            val resumed = paused.advance(40_000, motion, destinationInput = true)
            assertEquals(50_000L, resumed.closesAt)
            assertTrue(resumed.advance(49_999, motion, destinationInput = true).isOpen)
            assertFalse(resumed.advance(50_000, motion, destinationInput = true).isOpen)
            assertEquals(50_000L, paused.advance(40_000, motion).closesAt)
        }
        assertFalse(paused.advance(2_000, MotionState.FAST, destinationInput = true).isOpen)
    }

    @Test
    fun stoppingWhileEnteringDestinationPausesUntilMovementResumes() {
        val walking = MotionState.WALKING
        val stopped = MotionState.STOPPED
        val entering = closed.tap(HudTarget.MAP, 0, walking).advance(1_000, walking, destinationInput = true)
        val paused = entering.advance(5_000, stopped, destinationInput = true)
        assertEquals(null, paused.closesAt)
        assertTrue(paused.advance(40_000, stopped, destinationInput = true).isOpen)
        val resumed = paused.advance(40_000, walking, destinationInput = true)
        assertEquals(50_000L, resumed.closesAt)
    }

    @Test
    fun fastClosesImmediatelyAndAPlainMapOrOutsideTapClosesManually() {
        val fast = MotionState.FAST
        val stopped = MotionState.STOPPED
        val opened = closed.tap(HudTarget.MAP, 0, stopped)
        assertEquals(closed, opened.advance(1, fast))
        assertEquals(closed, closed.tap(HudTarget.MAP, 1, fast))
        assertEquals(closed, opened.tap(HudTarget.BIGMAP, 1, stopped))
        assertEquals(closed, opened.tap(HudTarget.OUTSIDE, 1, stopped))
    }

    @Test
    fun everyMapOperationRenewsTheDeadlineAndRespectsStoppedDestinationInput() {
        val buttons = HudPlacement.bigMapButtons.map { it.target } + destinationCandidateTargets + HudTarget.NAV_CANCEL
        for (motion in listOf(MotionState.STOPPED, MotionState.WALKING, MotionState.NO_FIX)) {
            val opened = closed.tap(HudTarget.MAP, 0, motion)
            val deadline = if (motion == MotionState.STOPPED) 35_000L else 15_000L
            buttons.forEach { assertEquals(it.name, deadline, opened.tap(it, 5_000L, motion).closesAt) }
            // Dragging and holding use the same interaction path without synthesizing a button tap.
            assertEquals(deadline, opened.interact(5_000L, motion, destinationInput = false).closesAt)
            val entering = opened.interact(5_000L, motion, destinationInput = true)
            assertEquals(if (motion == MotionState.STOPPED) null else deadline, entering.closesAt)
            assertEquals(closed, entering.interact(5_001L, MotionState.FAST, destinationInput = true))
            assertEquals(closed, opened.interact(deadline, motion, destinationInput = false))
        }
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
