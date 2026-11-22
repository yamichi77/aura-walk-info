package io.github.yamichi77.aurawalkinfo.location

import io.github.yamichi77.aurawalkinfo.safety.MotionState

enum class FixDecision { ACCEPTED, POOR_ACCURACY, TOO_LITTLE_MOVEMENT, NOT_WALKING }

class DistanceTracker(private val distanceCalculator: DistanceCalculator) {
    private var anchor: LocationSample? = null
    var distanceMeters: Double = 0.0
        private set

    fun update(motionState: MotionState, sample: LocationSample? = null): FixDecision? {
        when (motionState) {
            MotionState.FAST, MotionState.NO_FIX -> anchor = null
            MotionState.STOPPED -> Unit
            MotionState.WALKING -> {
                if (sample == null) return null
                if (sample.accuracyMeters > MAX_ACCURACY_METERS) return FixDecision.POOR_ACCURACY
                val previous = anchor
                if (previous != null) {
                    val distance = distanceCalculator.metersBetween(previous, sample)
                    if (distance < maxOf(MIN_STEP_METERS, sample.accuracyMeters.toDouble())) {
                        return FixDecision.TOO_LITTLE_MOVEMENT
                    }
                    distanceMeters += distance
                }
                anchor = sample
                return FixDecision.ACCEPTED
            }
        }
        return sample?.let {
            if (it.accuracyMeters > MAX_ACCURACY_METERS) FixDecision.POOR_ACCURACY
            else FixDecision.NOT_WALKING
        }
    }

    private companion object {
        const val MAX_ACCURACY_METERS = 20f
        const val MIN_STEP_METERS = 5.0
    }
}
