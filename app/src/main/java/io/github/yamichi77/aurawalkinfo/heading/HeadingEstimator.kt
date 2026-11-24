package io.github.yamichi77.aurawalkinfo.heading

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class HeadTracking { TRACKING, PAUSED, STOPPED }

internal data class HeadPose(
    val timeMillis: Long,
    val tracking: HeadTracking,
    val xMeters: Double? = null,
    val zMeters: Double? = null,
    val yawDegrees: Double? = null,
    val perceptionPosition: androidx.xr.runtime.math.Vector3? = null,
)

internal data class HeadingFix(
    val timeMillis: Long,
    val bearingDegrees: Double?,
    val bearingAccuracyDegrees: Double?,
    val speedMps: Double?,
)

// The clockwise yaw sign agreed with the T29 outdoor field log.
internal object HeadingAxes {
    const val FORWARD_Z = -1
    const val RIGHT_X = 1
    const val YAW_TO_CLOCKWISE = -1

    fun travelBearing(dx: Double, dz: Double): Double =
        Math.toDegrees(atan2(RIGHT_X * dx, FORWARD_Z * dz))
}

internal data class NorthEstimate(val offsetDegrees: Double, val uncertaintyDegrees: Double) {
    fun bearingForYaw(yawDegrees: Double): Double =
        normalizeDegrees(offsetDegrees + HeadingAxes.YAW_TO_CLOCKWISE * yawDegrees)
}

/** GPS and pose times use the same monotonic clock. Call from a single dispatcher. */
internal class HeadingEstimator {
    private data class TimedAngle(val timeMillis: Long, val degrees: Double)

    private val poses = ArrayDeque<HeadPose>()
    private val bearings = ArrayDeque<TimedAngle>()
    private val offsets = ArrayDeque<TimedAngle>()
    private var north: NorthEstimate? = null

    fun onFix(fix: HeadingFix, walking: Boolean) {
        val time = fix.timeMillis
        val pose = trackedPoseNear(time) ?: return

        val bearing = fix.bearingDegrees
        val speed = fix.speedMps
        val accuracy = fix.bearingAccuracyDegrees
        if (!walking || bearing == null || !bearing.isFinite() ||
            speed == null || !speed.isFinite() || speed < 1.0 ||
            (accuracy != null && accuracy !in 0.0..30.0)
        ) {
            bearings.clear()
            return
        }
        if (bearings.lastOrNull()?.let { time <= it.timeMillis } == true) return
        bearings.addLast(TimedAngle(time, bearing))
        while (time - bearings.first().timeMillis > BEARING_WINDOW_MS) bearings.removeFirst()
        if (accuracy == null &&
            (bearings.size < 3 || circularStats(bearings.map { it.degrees }).deviation > 15.0)
        ) return
        if (offsets.lastOrNull()?.let { time - it.timeMillis < SAMPLE_INTERVAL_MS } == true) return

        val yaw = pose.yawDegrees ?: return
        if (!yaw.isFinite()) return
        val offset = normalizeDegrees(bearing - HeadingAxes.YAW_TO_CLOCKWISE * yaw)
        offsets.addLast(TimedAngle(time, offset))
        if (offsets.size > RECENT_SAMPLES) offsets.removeFirst()
        updateNorth()
    }

    fun onPose(pose: HeadPose) {
        val previous = poses.lastOrNull()?.timeMillis
        if (previous != null && pose.timeMillis < previous) return
        if (pose.tracking != HeadTracking.TRACKING) {
            reset()
            return
        }
        if (previous != null && pose.timeMillis - previous > MAX_POSE_GAP_MS) reset()
        poses.addLast(pose)
        // GPS callbacks can arrive after the corresponding head sample.
        while (pose.timeMillis - poses.first().timeMillis > POSE_HISTORY_MS) poses.removeFirst()
    }

    fun trackedPoseNear(timeMillis: Long): HeadPose? {
        val first = poses.firstOrNull() ?: return null
        if (timeMillis < first.timeMillis) return null
        return poses.minBy { abs(timeMillis - it.timeMillis) }
            .takeIf { abs(timeMillis - it.timeMillis) <= MAX_POSE_GAP_MS }
    }

    fun estimate(nowMillis: Long): NorthEstimate? {
        val lastPose = poses.lastOrNull() ?: return null
        if (nowMillis - lastPose.timeMillis > MAX_POSE_GAP_MS) return null
        val estimate = north ?: return null
        val ageMinutes = (nowMillis - offsets.last().timeMillis).coerceAtLeast(0) / 60_000.0
        return estimate.copy(uncertaintyDegrees = estimate.uncertaintyDegrees + ageMinutes)
    }

    fun reset() {
        poses.clear()
        bearings.clear()
        offsets.clear()
        north = null
    }

    private fun updateNorth() {
        north = null
        if (offsets.size < MIN_SAMPLES) return
        val mean = circularStats(offsets.map { it.degrees }).mean
        val retained = offsets.filter { angularDistance(mean, it.degrees) <= 30.0 }
        if (retained.size < MIN_SAMPLES) return
        val (offset, deviation) = circularStats(retained.map { it.degrees })
        // Consecutive samples are correlated, so use an effective sample size of one fifth.
        val floor = if (retained.size < FULL_CONFIDENCE_SAMPLES) 12.0 else 8.0
        val uncertainty = (deviation / sqrt(retained.size / 5.0)).coerceAtLeast(floor)
        north = NorthEstimate(offset, uncertainty)
    }

    companion object {
        const val MAX_POSE_GAP_MS = 350L
        private const val POSE_HISTORY_MS = 1_350L
        private const val BEARING_WINDOW_MS = 3_000L
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val MIN_SAMPLES = 8
        private const val FULL_CONFIDENCE_SAMPLES = 20
        private const val RECENT_SAMPLES = 120
    }
}

internal fun normalizeDegrees(angle: Double): Double = ((angle % 360.0) + 360.0) % 360.0

private fun angularDistance(a: Double, b: Double): Double =
    abs(normalizeDegrees(a - b + 180.0) - 180.0)

private data class CircularStats(val mean: Double, val deviation: Double)

private fun circularStats(angles: List<Double>): CircularStats {
    val x = angles.sumOf { cos(Math.toRadians(it)) }
    val y = angles.sumOf { sin(Math.toRadians(it)) }
    val length = (hypot(x, y) / angles.size).coerceIn(1e-15, 1.0)
    return CircularStats(
        normalizeDegrees(Math.toDegrees(atan2(y, x))),
        Math.toDegrees(sqrt(-2.0 * ln(length))),
    )
}
