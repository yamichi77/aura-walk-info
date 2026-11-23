package io.github.yamichi77.aurawalkinfo.safety

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

internal const val HideHudOnHeadMotion = false

internal class HeadMotionGate {
    data class Rotation(val x: Double, val y: Double, val z: Double, val w: Double) {
        private fun dot(other: Rotation) = x * other.x + y * other.y + z * other.z + w * other.w

        fun angleTo(other: Rotation): Double {
            // q and −q describe the same rotation. Use the shorter arc.
            val cosine = abs(dot(other)) / sqrt(dot(this) * other.dot(other))
            return Math.toDegrees(2 * acos(cosine.coerceIn(0.0, 1.0)))
        }
    }

    private data class PoseSample(val time: Long, val rotation: Rotation)
    private data class Interval(val start: Long, val end: Long, val speed: Double)

    private val intervals = ArrayDeque<Interval>()
    private var previous: PoseSample? = null
    private var belowThresholdSince: Long? = null
    var hiddenSinceNanos: Long? = null
        private set

    fun update(time: Long, rotation: Rotation?): Double? {
        if (rotation == null) {
            previous = null
            intervals.clear()
            belowThresholdSince = null
            hiddenSinceNanos = null
            return null
        }

        val last = previous
        if (last != null && time <= last.time) return null
        previous = PoseSample(time, rotation)
        if (last == null || time - last.time > AVERAGE_NANOS) {
            // A frame gap supplies no evidence of sustained slow motion.
            intervals.clear()
            belowThresholdSince = null
            return null
        }

        val speed = last.rotation.angleTo(rotation) * NANOS_PER_SECOND / (time - last.time)
        intervals.addLast(Interval(last.time, time, speed))
        val cutoff = time - AVERAGE_NANOS
        while (intervals.first().end <= cutoff) intervals.removeFirst()
        val windowStart = maxOf(cutoff, intervals.first().start)
        val meanSpeed = intervals.sumOf { it.speed * (it.end - maxOf(windowStart, it.start)) } /
            (time - windowStart)

        if (meanSpeed > HideDegreesPerSecond && hiddenSinceNanos == null) hiddenSinceNanos = time
        if (hiddenSinceNanos != null && meanSpeed < ShowDegreesPerSecond) {
            val since = belowThresholdSince ?: time
            belowThresholdSince = since
            if (time - since >= SETTLE_NANOS) {
                hiddenSinceNanos = null
                belowThresholdSince = null
            }
        } else {
            belowThresholdSince = null
        }
        return meanSpeed
    }

    companion object {
        const val HideDegreesPerSecond = 30.0
        const val ShowDegreesPerSecond = 15.0
        const val StationaryDegreesPerSecond = 5.0
        const val HideMillis = 100
        const val ShowMillis = 300
        private const val AVERAGE_NANOS = 100_000_000L
        private const val SETTLE_NANOS = 300_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
