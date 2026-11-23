package io.github.yamichi77.aurawalkinfo.safety

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.haversine
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionClassifierTest {
    private val classifier = MotionClassifier(haversine)

    @Test
    fun stopsFiveSecondsAfterAverageDropsBelowThreshold() {
        assertEquals(MotionState.WALKING, fix(0, 1.3f))
        // t=4 is the first window with only stop samples.
        for (second in 1..8) assertEquals(MotionState.WALKING, fix(second, 0f))
        assertEquals(MotionState.STOPPED, fix(9, 0f))
    }

    @Test
    fun hysteresisRetainsWalkingOrStoppedAtPointFour() {
        assertEquals(MotionState.STOPPED, fix(0, 0.4f))
        assertEquals(MotionState.WALKING, fix(1, 1.3f))
        for (second in 2..10) assertEquals(MotionState.WALKING, fix(second, 0.4f))
    }

    @Test
    fun fastAndRecoveryBothRequireFiveSecondsOfMatchingAverage() {
        for (second in 0..4) assertEquals(MotionState.WALKING, fix(second, 5f))
        assertEquals(MotionState.FAST, fix(5, 5f))
        // [5, 5, 5, 1.3] averages below 4.5 starting at t=6.
        for (second in 6..10) assertEquals(MotionState.FAST, fix(second, 1.3f))
        assertEquals(MotionState.WALKING, fix(11, 1.3f))
    }

    @Test
    fun losesFixAtTenSecondsOrWhenAccuracyExceedsThirtyMeters() {
        assertEquals(MotionState.NO_FIX, classifier.onTick(0))
        assertEquals(MotionState.WALKING, fix(0, 1f, 30f))
        assertEquals(MotionState.WALKING, classifier.onTick(9_999))
        assertEquals(MotionState.NO_FIX, classifier.onTick(10_000))
        assertEquals(MotionState.STOPPED, fix(11, 0.4f))
        assertEquals(MotionState.NO_FIX, fix(12, 2f, 35f))
    }

    @Test
    fun fastHasPriorityOverPoorAccuracyAndTimeoutAndNeedsSlowSamplesToRecover() {
        for (second in 0..4) assertEquals(MotionState.NO_FIX, fix(second, 5f, 35f))
        assertEquals(MotionState.FAST, fix(5, 5f, 35f))
        assertEquals(MotionState.FAST, classifier.onTick(15_000))
        for (second in 16..20) assertEquals(MotionState.FAST, fix(second, 0f, 35f))
        assertEquals(MotionState.NO_FIX, fix(21, 0f, 35f))
    }

    @Test
    fun sparseStationaryFixesConfirmStopAndRetainIt() {
        assertEquals(MotionState.WALKING, fix(0, 1.3f, 12f))
        assertEquals(MotionState.WALKING, fix(6, 0.1f, 12f))
        for (second in 7..10) assertEquals(MotionState.WALKING, classifier.onTick(second * 1_000L))
        assertEquals(0.1, checkNotNull(classifier.averageSpeedMps), 0.001)
        assertEquals(MotionState.STOPPED, fix(12, 0.2f, 12f))
        assertEquals(MotionState.STOPPED, fix(18, 0.4f, 12f))
        assertEquals(MotionState.STOPPED, fix(24, 0.4f, 12f))
    }

    @Test
    fun walkingRemainsWalkingWithFixesEveryTwoSeconds() {
        for (second in 0..12 step 2) {
            assertEquals(MotionState.WALKING, fix(second, 1.3f, 12f))
            assertEquals(MotionState.WALKING, classifier.onTick((second + 1) * 1_000L))
        }
    }

    @Test
    fun poorAccuracyUsesReportedSpeedButDoesNotInferSpeed() {
        assertEquals(MotionState.STOPPED, classifier.onLocation(syntheticFix(0, speed = null)))
        val firstPoor = syntheticFix(1_000, 100.0, accuracy = 250f, speed = null)
        assertEquals(MotionState.NO_FIX, classifier.onLocation(firstPoor))
        assertNull(classifier.latestSpeedMps)

        assertEquals(MotionState.NO_FIX, fix(2, 3f, 250f))
        assertEquals(3.0, checkNotNull(classifier.latestSpeedMps), 0.001)
        assertEquals(3.0, checkNotNull(classifier.averageSpeedMps), 0.001)
        assertEquals(MotionState.NO_FIX, classifier.onTick(6_000))
        assertNull(classifier.averageSpeedMps)

        val firstGood = syntheticFix(7_000, 600.0, accuracy = 12f, speed = null)
        assertEquals(MotionState.STOPPED, classifier.onLocation(firstGood))
        assertNull(classifier.latestSpeedMps)
    }

    @Test
    fun indoorSpeedSpikeRecoversToStoppedWithSparseFixes() {
        assertEquals(MotionState.NO_FIX, fix(0, 2f, 250f))
        assertEquals(MotionState.NO_FIX, fix(6, 3f, 250f))
        assertEquals(MotionState.NO_FIX, fix(12, 2.5f, 250f))
        assertEquals(MotionState.WALKING, fix(18, 2.06f, 12f))
        assertEquals(MotionState.WALKING, fix(24, 0.1f, 12f))
        for (second in 25..28) assertEquals(MotionState.WALKING, classifier.onTick(second * 1_000L))
        assertEquals(MotionState.STOPPED, classifier.onTick(29_000))
        assertEquals(MotionState.STOPPED, fix(30, 0.4f, 12f))
        assertEquals(MotionState.STOPPED, fix(36, 0.2f, 12f))
    }

    @Test
    fun computesMissingSpeedFromConsecutivePositions() {
        assertEquals(MotionState.STOPPED, classifier.onLocation(syntheticFix(0, speed = null)))
        assertEquals(MotionState.WALKING, classifier.onLocation(syntheticFix(1_000, 1.3, speed = null)))
        assertEquals(1.3, checkNotNull(classifier.latestSpeedMps), 0.001)
        assertEquals(MotionState.STOPPED, classifier.onLocation(syntheticFix(11_000, 500.0, speed = null)))
    }

    private fun fix(second: Int, speed: Float, accuracy: Float = 5f): MotionState =
        classifier.onLocation(syntheticFix(second * 1_000L, accuracy = accuracy, speed = speed))
}
