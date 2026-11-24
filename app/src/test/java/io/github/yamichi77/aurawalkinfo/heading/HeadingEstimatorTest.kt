package io.github.yamichi77.aurawalkinfo.heading

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingEstimatorTest {
    @Test
    fun estimatesKnownOffsetWithStationaryArPositionAndOccasionalSideGlances() {
        val walk = Walk()
        repeat(120) { i ->
            val bearing = listOf(10.0, 110.0, 240.0)[i / 40]
            val headTurn = if (i % 10 == 0) 60.0 else if (i % 2 == 0) 10.0 else -10.0
            walk.sample(bearing, yaw = 154.0 - bearing - headTurn)
        }
        val estimate = checkNotNull(walk.estimate())
        assertAngle(154.0, estimate.offsetDegrees, 5.0)
        assertAngle(184.0, estimate.bearingForYaw(-30.0), 5.0)
    }

    @Test
    fun requiresTwentySamplesAtLeastOneSecondApart() {
        val walk = Walk()
        repeat(37) { walk.sample(interval = 500) }
        assertNull(walk.estimate())
        walk.sample(interval = 500)
        assertNull(walk.estimate())
        walk.sample(interval = 500)
        assertAngle(37.0, checkNotNull(walk.estimate()).offsetDegrees)
    }

    @Test
    fun rejectsSlowImpreciseAndNonWalkingFixes() {
        val walk = Walk()
        repeat(20) {
            walk.sample(bearing = 100.0, speed = 0.99)
            walk.sample(bearing = 100.0, bearingAccuracy = 30.01)
            walk.sample(bearing = 100.0, walking = false)
        }
        assertNull(walk.estimate())
        repeat(20) { walk.sample(speed = 1.0, bearingAccuracy = 30.0) }
        assertAngle(37.0, checkNotNull(walk.estimate()).offsetDegrees)
    }

    @Test
    fun usesRecentCircularBearingDeviationWhenAccuracyIsMissing() {
        val turning = Walk()
        repeat(24) { i -> turning.sample(if (i % 2 == 0) 20.0 else 70.0, bearingAccuracy = null) }
        assertNull(turning.estimate())

        val walk = Walk()
        repeat(21) { i -> walk.sample(if (i % 2 == 0) 359.0 else 1.0, bearingAccuracy = null) }
        assertNull(walk.estimate())
        walk.sample(1.0, bearingAccuracy = null)
        assertAngle(0.0, checkNotNull(walk.estimate()).offsetDegrees, 0.2)
    }

    @Test
    fun requiresTwentySamplesAfterRejectingOutliers() {
        val walk = Walk()
        repeat(19) { walk.sample() }
        walk.sample(bearing = 97.0)
        assertNull(walk.estimate())
        walk.sample()
        assertAngle(37.0, checkNotNull(walk.estimate()).offsetDegrees)
    }

    @Test
    fun usesCircularMeanAndUncertaintyFloorAcrossNorthAndAgesByOneDegreePerMinute() {
        val walk = Walk()
        repeat(20) { i -> walk.sample(if (i % 2 == 0) 358.0 else 2.0) }
        val before = checkNotNull(walk.estimate())
        assertAngle(0.0, before.offsetDegrees)
        assertEquals(8.0, before.uncertaintyDegrees, 1e-6)
        walk.advance(60_000)
        assertEquals(9.0, checkNotNull(walk.estimate()).uncertaintyDegrees, 1e-6)
    }

    @Test
    fun uncertaintyUsesRetainedScatterAndOneFifthOfTheSampleCount() {
        val walk = Walk()
        repeat(20) { i -> walk.sample(if (i % 2 == 0) 17.0 else 57.0) }
        walk.sample(bearing = 97.0)
        assertAngle(37.0, checkNotNull(walk.estimate()).offsetDegrees)
        assertEquals(20.2088406 / sqrt(20.0 / 5), checkNotNull(walk.estimate()).uncertaintyDegrees, 1e-6)
    }

    @Test
    fun keepsOnlyTheMostRecent120Samples() {
        val walk = Walk()
        repeat(120) { walk.sample(50.0) }
        repeat(120) { walk.sample(70.0) }
        assertAngle(70.0, checkNotNull(walk.estimate()).offsetDegrees)
    }

    @Test
    fun usesYawAtFixTimeWhenGpsCallbackArrivesLater() {
        val walk = Walk()
        repeat(20) { walk.sample(deliveryDelay = 600) }
        assertAngle(37.0, checkNotNull(walk.estimate()).offsetDegrees)
    }

    @Test
    fun trackingLossAndPoseGapsDiscardTheEstimate() {
        val walk = Walk()
        repeat(20) { walk.sample() }
        walk.estimator.onPose(HeadPose(walk.time, HeadTracking.PAUSED))
        assertNull(walk.estimate())
        repeat(19) { walk.sample(80.0) }
        assertNull(walk.estimate())
        walk.sample(80.0)
        assertAngle(80.0, checkNotNull(walk.estimate()).offsetDegrees)
        walk.time += HeadingEstimator.MAX_POSE_GAP_MS + 1
        assertNull(walk.estimate())
        walk.sample()
        assertNull(walk.estimate())
    }

    private fun assertAngle(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertEquals(0.0, normalizeDegrees(actual - expected + 180) - 180, tolerance)
    }

    private class Walk {
        val estimator = HeadingEstimator()
        var time = 0L

        fun sample(
            bearing: Double = 37.0,
            yaw: Double = 0.0,
            speed: Double = 1.4,
            bearingAccuracy: Double? = 8.0,
            walking: Boolean = true,
            interval: Long = 1_000L,
            deliveryDelay: Long = 0L,
        ) {
            advance(interval, yaw)
            val fix = HeadingFix(time, bearing, bearingAccuracy, speed)
            advance(deliveryDelay, yaw + 45.0)
            estimator.onFix(fix, walking)
        }

        fun advance(millis: Long, yaw: Double = 0.0) {
            repeat((millis / 100).toInt()) {
                time += 100
                estimator.onPose(HeadPose(time, HeadTracking.TRACKING, xMeters = 0.0, zMeters = 0.0, yawDegrees = yaw))
            }
        }

        fun estimate() = estimator.estimate(time)
    }
}
