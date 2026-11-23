package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.safety.MotionState

internal sealed interface WalkPhase {
    data object Standby : WalkPhase
    data class Starting(val endsAt: Long) : WalkPhase
    data object Measuring : WalkPhase
    data class Ending(val endsAt: Long) : WalkPhase
    data object Summary : WalkPhase

    val deadline: Long?
        get() = when (this) {
            is Starting -> endsAt
            is Ending -> endsAt
            else -> null
        }

    fun remainingSeconds(now: Long): Int? = deadline?.let {
        ((it - now).coerceAtLeast(0) + 999).div(1_000).toInt()
    }

    fun advance(now: Long): WalkPhase = when {
        deadline?.let { now >= it } != true -> this
        this is Starting -> Measuring
        else -> Summary
    }

    fun tap(now: Long, motion: MotionState): WalkPhase {
        val current = advance(now)
        if (motion == MotionState.FAST) return current
        return when (current) {
            Standby, Summary -> Starting(now + 3_000)
            is Starting -> Standby
            Measuring -> Ending(now + 3_000)
            is Ending -> Measuring
        }
    }
}
