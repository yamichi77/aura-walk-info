package io.github.yamichi77.aurawalkinfo.safety

import io.github.yamichi77.aurawalkinfo.location.DistanceCalculator
import io.github.yamichi77.aurawalkinfo.location.LocationSample

enum class MotionState { WALKING, STOPPED, FAST, NO_FIX }

internal object MotionThresholds {
    const val SPEED_WINDOW_MILLIS = 3_000L
    const val CONFIRM_MILLIS = 5_000L
    const val NO_FIX_MILLIS = 10_000L
    const val MAX_FIX_ERROR_METERS = 30f
    const val FAST_MPS = 4.5
    const val WALKING_MPS = 0.5
    const val STOPPED_MPS = 0.3

    fun hasFix(sample: LocationSample, nowMillis: Long): Boolean =
        nowMillis - sample.elapsedRealtimeMillis < NO_FIX_MILLIS &&
            sample.accuracyMeters <= MAX_FIX_ERROR_METERS
}

class MotionClassifier(private val distanceCalculator: DistanceCalculator) {
    private data class Speed(val timeMillis: Long, val metersPerSecond: Double)

    private val speeds = ArrayDeque<Speed>()
    private var lastSample: LocationSample? = null
    private var state = MotionState.NO_FIX
    private var fastSince: Long? = null
    private var slowSince: Long? = null
    private var stoppedSince: Long? = null
    var latestSpeedMps: Double? = null
        private set
    internal var averageSpeedMps: Double? = null
        private set

    fun onLocation(sample: LocationSample, nowMillis: Long = sample.elapsedRealtimeMillis): MotionState {
        val previous = lastSample
        if (previous != null) {
            val gap = sample.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
            if (gap <= 0) return onTick(nowMillis)
            if (gap > MotionThresholds.SPEED_WINDOW_MILLIS) {
                speeds.clear()
                clearConfirmation()
            }
            if (gap >= MotionThresholds.NO_FIX_MILLIS && state != MotionState.FAST) {
                state = MotionState.NO_FIX
            }
        }

        latestSpeedMps = sample.speedMps?.toDouble() ?: previous?.let {
            val gap = sample.elapsedRealtimeMillis - it.elapsedRealtimeMillis
            if (gap < MotionThresholds.NO_FIX_MILLIS) {
                distanceCalculator.metersBetween(it, sample) * 1_000 / gap
            } else {
                null
            }
        }
        lastSample = sample
        latestSpeedMps?.let { speeds.addLast(Speed(sample.elapsedRealtimeMillis, it)) }
        return onTick(nowMillis)
    }

    fun onTick(nowMillis: Long): MotionState {
        while (speeds.firstOrNull()?.let { nowMillis - it.timeMillis > MotionThresholds.SPEED_WINDOW_MILLIS } == true) {
            speeds.removeFirst()
        }
        val average = if (speeds.isEmpty()) null else speeds.map { it.metersPerSecond }.average()
        averageSpeedMps = average
        fastSince = trackSince(average != null && average > MotionThresholds.FAST_MPS, fastSince, nowMillis)
        slowSince = trackSince(average != null && average <= MotionThresholds.FAST_MPS, slowSince, nowMillis)

        val hasFix = lastSample?.let { MotionThresholds.hasFix(it, nowMillis) } == true
        stoppedSince = trackSince(
            hasFix && average != null && average < MotionThresholds.STOPPED_MPS,
            stoppedSince,
            nowMillis,
        )

        state = when {
            state == MotionState.FAST && !confirmed(slowSince, nowMillis) -> MotionState.FAST
            confirmed(fastSince, nowMillis) -> MotionState.FAST
            !hasFix -> MotionState.NO_FIX
            state == MotionState.NO_FIX || state == MotionState.FAST ->
                if (average != null && average >= MotionThresholds.WALKING_MPS) MotionState.WALKING
                else MotionState.STOPPED
            confirmed(stoppedSince, nowMillis) -> MotionState.STOPPED
            average != null && average >= MotionThresholds.WALKING_MPS -> MotionState.WALKING
            else -> state
        }
        return state
    }

    private fun clearConfirmation() {
        fastSince = null
        slowSince = null
        stoppedSince = null
    }

    private fun trackSince(condition: Boolean, since: Long?, nowMillis: Long): Long? =
        if (condition) since ?: nowMillis else null

    private fun confirmed(since: Long?, nowMillis: Long): Boolean =
        since != null && nowMillis - since >= MotionThresholds.CONFIRM_MILLIS
}
