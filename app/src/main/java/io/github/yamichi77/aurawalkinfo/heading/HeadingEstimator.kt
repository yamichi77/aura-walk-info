package io.github.yamichi77.aurawalkinfo.heading

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
    val accuracyMeters: Double,
)

// T4 initial convention. Verify the axes and yaw sign on the glasses at acceptance.
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
    private val poses = ArrayDeque<HeadPose>()
    private val fixes = ArrayDeque<HeadingFix>()
    private val offsets = ArrayDeque<Pair<Long, Double>>()
    private var windowStart: Long? = null
    private var lastPoseTime: Long? = null

    fun onFix(fix: HeadingFix) {
        val poseTime = lastPoseTime ?: return
        if (fix.timeMillis < poses.first().timeMillis ||
            fix.timeMillis < (windowStart ?: fix.timeMillis) ||
            fix.timeMillis - poseTime > MAX_POSE_GAP_MS ||
            fixes.lastOrNull()?.let { fix.timeMillis <= it.timeMillis } == true
        ) return
        if (windowStart == null) windowStart = fix.timeMillis
        fixes.addLast(fix)
    }

    fun onPose(pose: HeadPose) {
        val previous = lastPoseTime
        if (previous != null && pose.timeMillis < previous) return
        if (pose.tracking != HeadTracking.TRACKING ||
            pose.xMeters?.isFinite() != true || pose.zMeters?.isFinite() != true
        ) {
            reset()
            return
        }
        if (previous != null && pose.timeMillis - previous > MAX_POSE_GAP_MS) reset()
        lastPoseTime = pose.timeMillis
        poses.addLast(pose)
        val start = windowStart
        if (start == null) {
            while (pose.timeMillis - poses.first().timeMillis > FIX_GRACE_MS + MAX_POSE_GAP_MS) {
                poses.removeFirst()
            }
            return
        }
        val end = start + WINDOW_MS
        if (pose.timeMillis < end + FIX_GRACE_MS) return

        val points = poses.filter { it.timeMillis in start..end }
        val observations = fixes.filter { it.timeMillis in start..end }
        windowOffset(start, end, points, observations)?.let { offset ->
            offsets.addLast(end to offset)
            if (offsets.size > RECENT_WINDOWS) offsets.removeFirst()
        }
        windowStart = end
        while (poses.first().timeMillis < end) poses.removeFirst()
        while (fixes.firstOrNull()?.let { it.timeMillis < end } == true) fixes.removeFirst()
    }

    fun estimate(nowMillis: Long): NorthEstimate? {
        val lastPose = lastPoseTime ?: return null
        if (nowMillis - lastPose > MAX_POSE_GAP_MS || offsets.isEmpty()) return null
        val values = offsets.map { it.second }
        val median = values.minBy { candidate -> values.sumOf { angularDistance(candidate, it) } }
        val retained = offsets.filter { angularDistance(median, it.second) < 20.0 }
        val angles = retained.map { it.second }
        // A single window cannot measure scatter. Budget the allowed GPS bearing error per window.
        val uncertainty = (hypot(circularDeviation(angles), MAX_BEARING_ERROR_DEG) /
            sqrt(angles.size.toDouble())).coerceAtLeast(5.0)
        val ageMinutes = (nowMillis - retained.last().first).coerceAtLeast(0) / 60_000.0
        return NorthEstimate(circularMean(angles), uncertainty + ageMinutes)
    }

    fun reset() {
        poses.clear()
        fixes.clear()
        offsets.clear()
        windowStart = null
        lastPoseTime = null
    }

    private fun windowOffset(
        start: Long,
        end: Long,
        points: List<HeadPose>,
        observations: List<HeadingFix>,
    ): Double? {
        if (points.size < 2 || observations.size < 3 ||
            points.first().timeMillis - start > MAX_POSE_GAP_MS ||
            end - points.last().timeMillis > MAX_POSE_GAP_MS ||
            observations.first().timeMillis - start > MAX_FIX_EDGE_MS ||
            end - observations.last().timeMillis > MAX_FIX_EDGE_MS ||
            observations.zipWithNext().any { (a, b) -> b.timeMillis - a.timeMillis > MAX_FIX_GAP_MS }
        ) return null
        if (observations.any {
                it.bearingDegrees?.isFinite() != true || it.speedMps?.let { speed -> speed.isFinite() && speed >= 0.8 } != true ||
                    it.accuracyMeters !in 0.0..30.0 ||
                    it.bearingAccuracyDegrees?.let { accuracy -> accuracy !in 0.0..MAX_BEARING_ERROR_DEG } == true
            }
        ) return null
        val bearings = observations.map { requireNotNull(it.bearingDegrees) }
        if (circularDeviation(bearings) > 10.0) return null

        val dx = points.last().xMeters!! - points.first().xMeters!!
        val dz = points.last().zMeters!! - points.first().zMeters!!
        val displacement = hypot(dx, dz)
        if (displacement < 3.0) return null
        val path = points.zipWithNext().sumOf { (a, b) ->
            hypot(b.xMeters!! - a.xMeters!!, b.zMeters!! - a.zMeters!!)
        }
        if (displacement / path < 0.9) return null
        return normalizeDegrees(circularMean(bearings) - HeadingAxes.travelBearing(dx, dz))
    }

    companion object {
        const val MAX_POSE_GAP_MS = 350L
        private const val WINDOW_MS = 5_000L
        private const val FIX_GRACE_MS = 1_000L
        private const val MAX_FIX_EDGE_MS = 1_500L
        private const val MAX_FIX_GAP_MS = 2_000L
        private const val RECENT_WINDOWS = 20
        private const val MAX_BEARING_ERROR_DEG = 20.0
    }
}

internal fun normalizeDegrees(angle: Double): Double = ((angle % 360.0) + 360.0) % 360.0

private fun angularDistance(a: Double, b: Double): Double =
    kotlin.math.abs(normalizeDegrees(a - b + 180.0) - 180.0)

private fun circularMean(angles: List<Double>): Double {
    val x = angles.sumOf { cos(Math.toRadians(it)) }
    val y = angles.sumOf { sin(Math.toRadians(it)) }
    return normalizeDegrees(Math.toDegrees(atan2(y, x)))
}

private fun circularDeviation(angles: List<Double>): Double {
    val x = angles.sumOf { cos(Math.toRadians(it)) }
    val y = angles.sumOf { sin(Math.toRadians(it)) }
    val length = (hypot(x, y) / angles.size).coerceIn(0.0, 1.0)
    return Math.toDegrees(sqrt(-2.0 * ln(length)))
}
