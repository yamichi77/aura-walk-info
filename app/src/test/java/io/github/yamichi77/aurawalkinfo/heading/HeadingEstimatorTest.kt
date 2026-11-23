package io.github.yamichi77.aurawalkinfo.heading

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingEstimatorTest {
    @Test
    fun estimatesKnownOffsetInDifferentTravelDirectionsRegardlessOfHeadYaw() {
        val directions = listOf(10.0, 110.0, 240.0)
        val steady = Walk()
        val lookingAround = Walk()
        directions.forEach { direction ->
            steady.straight(direction, direction + 37)
            lookingAround.straight(direction, direction + 37, lookAround = true)
        }
        val estimate = steady.estimate()!!
        assertAngle(37.0, estimate.offsetDegrees, 2.0)
        assertEquals(estimate, lookingAround.estimate())
        assertAngle(67.0, estimate.bearingForYaw(-30.0), 2.0)
    }

    @Test
    fun rejectsChangingGpsBearingEvenWhenBearingAccuracyIsMissing() {
        val walk = Walk()
        walk.segment({ 0.0 }, { if (it <= 2) 37.0 else 75.0 }, bearingAccuracy = null)
        assertNull(walk.estimate())
    }

    @Test
    fun rejectsCurvedArTrajectoryEvenWhenGpsBearingIsStable() {
        val walk = Walk()
        walk.segment({ if (it <= 25) 0.0 else 60.0 }, { 37.0 })
        assertNull(walk.estimate())
    }

    @Test
    fun rejectsSlowImpreciseShortAndSparseWindows() {
        val walks = listOf(
            Walk().apply { segment({ 0.0 }, { 37.0 }, speed = 0.7) },
            Walk().apply { segment({ 0.0 }, { 37.0 }, bearingAccuracy = 21.0) },
            Walk().apply { segment({ 0.0 }, { 37.0 }, locationAccuracy = 31.0) },
            Walk().apply { segment({ 0.0 }, { 37.0 }, stepMeters = 0.05) },
            Walk().apply { segment({ 0.0 }, { 37.0 }, fixEvery = 30) },
        )
        walks.forEach { assertNull(it.estimate()) }
    }

    @Test
    fun ignoresOutlierAndUsesCircularMeanAcrossZero() {
        val walk = Walk()
        listOf(358.0, 2.0, 359.0, 92.0, 1.0).forEach { walk.straight(0.0, it) }
        assertAngle(0.0, walk.estimate()!!.offsetDegrees, 0.01)
    }

    @Test
    fun acceptsStableGpsBearingWithoutAccuracyAndHandlesGpsNorthWrap() {
        val walk = Walk()
        walk.segment({ 0.0 }, { if (it % 2 == 0) 359.0 else 1.0 }, bearingAccuracy = null)
        assertAngle(0.0, walk.estimate()!!.offsetDegrees, 0.5)
    }

    @Test
    fun agesEstimateAndRecoversFromTrackingLossWithNewSamplesOnly() {
        val walk = Walk()
        walk.straight(0.0, 37.0)
        val before = walk.estimate()!!.uncertaintyDegrees
        assertTrue(before >= 20.0)
        walk.waitWithoutFixes(60_000)
        assertEquals(before + 1.0, walk.estimate()!!.uncertaintyDegrees, 0.001)
        walk.estimator.onPose(HeadPose(walk.time, HeadTracking.PAUSED))
        assertNull(walk.estimate())
        walk.straight(0.0, 80.0)
        assertAngle(80.0, walk.estimate()!!.offsetDegrees, 2.0)
        walk.waitWithoutFixes(100)
        assertNull(walk.estimator.estimate(walk.time + HeadingEstimator.MAX_POSE_GAP_MS + 1))
        walk.time += 1_000
        walk.waitWithoutFixes(100)
        assertNull(walk.estimate())
    }

    private fun assertAngle(expected: Double, actual: Double, tolerance: Double) {
        assertEquals(0.0, normalizeDegrees(actual - expected + 180) - 180, tolerance)
        assertTrue(actual >= 0 && actual < 360)
    }

    private class Walk {
        val estimator = HeadingEstimator()
        var time = 0L
        private var x = 0.0
        private var z = 0.0

        fun straight(travel: Double, bearing: Double, lookAround: Boolean = false) =
            segment({ travel }, { bearing }, lookAround = lookAround)

        fun segment(
            direction: (Int) -> Double,
            bearing: (Int) -> Double,
            speed: Double = 1.0,
            bearingAccuracy: Double? = 8.0,
            locationAccuracy: Double = 4.0,
            stepMeters: Double = 0.1,
            fixEvery: Int = 10,
            lookAround: Boolean = false,
        ) {
            // The last second lets the estimator receive delayed fixes and close the window.
            for (i in 0..60) {
                val yaw = if (lookAround) 50.0 * sin(i.toDouble()) else 0.0
                estimator.onPose(HeadPose(time, HeadTracking.TRACKING, x, z, yaw))
                if (i <= 50 && i % fixEvery == 0) {
                    estimator.onFix(HeadingFix(time, bearing(i / 10), bearingAccuracy, speed, locationAccuracy))
                }
                val angle = Math.toRadians(direction(i))
                x += stepMeters * sin(angle)
                z -= stepMeters * cos(angle)
                time += 100
            }
            // Keep tracking while anchoring the next test walk to a clean 5-second window.
            while (time % 5_000L != 0L) waitWithoutFixes(100)
        }

        fun waitWithoutFixes(millis: Long) {
            repeat((millis / 100).toInt()) {
                estimator.onPose(HeadPose(time, HeadTracking.TRACKING, x, z))
                time += 100
            }
        }

        fun estimate() = estimator.estimate(time)
    }
}
