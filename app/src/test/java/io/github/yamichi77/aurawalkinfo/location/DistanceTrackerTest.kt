package io.github.yamichi77.aurawalkinfo.location

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.haversine
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix

import io.github.yamichi77.aurawalkinfo.safety.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceTrackerTest {
    private val tracker = DistanceTracker(haversine)

    @Test
    fun walksFiveHundredMetersWithGpsNoise() {
        for (second in 0..385) {
            val noise = if (second % 2 == 0) 0.4 else -0.4
            tracker.update(MotionState.WALKING, syntheticFix(second * 1_000L, second * 1.3 + noise))
        }

        assertTrue(tracker.distanceMeters in 450.0..550.0)
    }

    @Test
    fun stoppedForFiveMinutesKeepsAnchorDespiteGpsDrift() {
        tracker.update(MotionState.WALKING, syntheticFix(0))
        for (second in 1..300) {
            val accuracy = 5f + second % 11
            val drift = if (second % 2 == 0) 7.0 else -7.0
            tracker.update(
                MotionState.STOPPED,
                syntheticFix(second * 1_000L, drift, accuracy = accuracy, speed = 0f),
            )
        }
        assertEquals(0.0, tracker.distanceMeters, 0.0)

        tracker.update(MotionState.WALKING, syntheticFix(301_000, 20.0))
        assertEquals(20.0, tracker.distanceMeters, 0.001)
    }

    @Test
    fun rejectsPoorAccuracyAndUsesCurrentFixForMinimumStep() {
        tracker.update(MotionState.WALKING, syntheticFix(0))
        tracker.update(MotionState.WALKING, syntheticFix(1_000, 100.0, accuracy = 25f))
        tracker.update(MotionState.WALKING, syntheticFix(2_000, 8.0, accuracy = 10f))
        assertEquals(0.0, tracker.distanceMeters, 0.0)
        tracker.update(MotionState.WALKING, syntheticFix(3_000, 12.0, accuracy = 10f))
        assertEquals(12.0, tracker.distanceMeters, 0.001)
    }

    @Test
    fun fastMovementAndLostFixDiscardAnchor() {
        tracker.update(MotionState.WALKING, syntheticFix(0))
        tracker.update(MotionState.WALKING, syntheticFix(1_000, 10.0))
        tracker.update(MotionState.FAST, syntheticFix(2_000, 500.0))
        tracker.update(MotionState.WALKING, syntheticFix(3_000, 600.0))
        assertEquals(10.0, tracker.distanceMeters, 0.001)

        tracker.update(MotionState.NO_FIX)
        tracker.update(MotionState.WALKING, syntheticFix(4_000, 800.0))
        assertEquals(10.0, tracker.distanceMeters, 0.001)
        tracker.update(MotionState.WALKING, syntheticFix(5_000, 810.0))
        assertEquals(20.0, tracker.distanceMeters, 0.001)
    }
}
