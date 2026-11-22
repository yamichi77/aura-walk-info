package io.github.yamichi77.aurawalkinfo.location

import io.github.yamichi77.aurawalkinfo.safety.MotionState

class DistanceTracker(private val distanceCalculator: DistanceCalculator) {
    private var anchor: LocationSample? = null
    var distanceMeters: Double = 0.0
        private set

    fun update(motionState: MotionState, sample: LocationSample? = null) {
        when (motionState) {
            MotionState.FAST, MotionState.NO_FIX -> anchor = null
            MotionState.STOPPED -> Unit
            MotionState.WALKING -> {
                if (sample == null || sample.accuracyMeters > MAX_ACCURACY_METERS) return
                val previous = anchor
                if (previous != null) {
                    val distance = distanceCalculator.metersBetween(previous, sample)
                    if (distance < maxOf(MIN_STEP_METERS, sample.accuracyMeters.toDouble())) return
                    distanceMeters += distance
                }
                anchor = sample
            }
        }
    }

    private companion object {
        const val MAX_ACCURACY_METERS = 20f
        const val MIN_STEP_METERS = 5.0
    }
}
