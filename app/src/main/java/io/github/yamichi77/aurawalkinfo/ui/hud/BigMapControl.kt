package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.safety.MotionState

internal enum class HudTarget {
    LEFT, MAP, BIGMAP, OUTSIDE, COUNTDOWN, BIGMAP_MODE, BIGMAP_ZOOM_IN, BIGMAP_ZOOM_OUT,
    BIGMAP_RECENTER,
    NAV_DESTINATION, NAV_CANDIDATE_1, NAV_CANDIDATE_2, NAV_CANDIDATE_3, NAV_START, NAV_CANCEL,
}

internal data class BigMapControl(
    val closesAt: Long? = null,
    private val enteringDestination: Boolean = false,
) {
    val isOpen: Boolean get() = closesAt != null || enteringDestination

    fun advance(now: Long, motion: MotionState, destinationInput: Boolean = false): BigMapControl {
        if (!isOpen) return this
        if (motion == MotionState.FAST || closesAt?.let { now >= it } == true) return BigMapControl()
        if (motion == MotionState.STOPPED) {
            return when {
                destinationInput -> BigMapControl(enteringDestination = true)
                enteringDestination -> BigMapControl(deadline(now, motion))
                else -> this
            }
        }
        val movementDeadline = deadline(now, motion)
        return BigMapControl(minOf(closesAt ?: movementDeadline, movementDeadline), destinationInput)
    }

    fun tap(target: HudTarget, now: Long, motion: MotionState, destinationInput: Boolean = false): BigMapControl {
        val current = advance(now, motion, destinationInput)
        return when (target) {
            HudTarget.MAP -> if (current.isOpen || motion == MotionState.FAST) current else {
                BigMapControl(deadline(now, motion)).advance(now, motion, destinationInput)
            }
            HudTarget.OUTSIDE -> BigMapControl()
            HudTarget.LEFT, HudTarget.COUNTDOWN, HudTarget.BIGMAP -> current
            HudTarget.BIGMAP_MODE, HudTarget.BIGMAP_ZOOM_IN, HudTarget.BIGMAP_ZOOM_OUT, HudTarget.BIGMAP_RECENTER,
            HudTarget.NAV_DESTINATION, HudTarget.NAV_CANDIDATE_1, HudTarget.NAV_CANDIDATE_2,
            HudTarget.NAV_CANDIDATE_3, HudTarget.NAV_START, HudTarget.NAV_CANCEL ->
                current.interact(now, motion, destinationInput)
        }
    }

    fun interact(now: Long, motion: MotionState, destinationInput: Boolean): BigMapControl {
        if (!advance(now, motion, destinationInput).isOpen) return BigMapControl()
        return if (motion == MotionState.STOPPED && destinationInput) {
            BigMapControl(enteringDestination = true)
        } else {
            BigMapControl(deadline(now, motion), destinationInput)
        }
    }

    private fun deadline(now: Long, motion: MotionState) =
        now + if (motion == MotionState.STOPPED) 30_000 else 10_000
}
