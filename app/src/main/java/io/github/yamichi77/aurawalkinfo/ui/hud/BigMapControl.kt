package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.safety.MotionState

internal enum class HudTarget { LEFT, MAP, BIGMAP, OUTSIDE }

internal data class BigMapControl(val closesAt: Long? = null) {
    val isOpen: Boolean get() = closesAt != null

    fun advance(now: Long, motion: MotionState): BigMapControl {
        val deadline = closesAt ?: return this
        if (now >= deadline || motion == MotionState.FAST) return BigMapControl()
        return when (motion) {
            MotionState.WALKING, MotionState.NO_FIX -> BigMapControl(minOf(deadline, now + 10_000))
            else -> this
        }
    }

    fun tap(target: HudTarget, now: Long, motion: MotionState): BigMapControl {
        val current = advance(now, motion)
        return when (target) {
            HudTarget.MAP -> if (current.isOpen || motion == MotionState.FAST) current else {
                BigMapControl(now + if (motion == MotionState.STOPPED) 30_000 else 10_000)
            }
            HudTarget.BIGMAP, HudTarget.OUTSIDE -> BigMapControl()
            HudTarget.LEFT -> current
        }
    }
}
