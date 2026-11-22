package io.github.yamichi77.aurawalkinfo.safety

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class HudVisibility(private val notice: SafetyNotice, showNotice: Boolean) {
    var noticePending by mutableStateOf(showNotice)
        private set
    var hidden by mutableStateOf(false)
        private set

    private var lastActionAt: Long? = null

    fun dismissNotice() {
        notice.markShown()
        noticePending = false
    }

    fun activate(motion: MotionState, eventTime: Long): String {
        if (motion == MotionState.FAST) return "ignored-fast"
        if (lastActionAt?.let { eventTime - it < 500L } == true) return "debounced"
        lastActionAt = eventTime
        if (noticePending) {
            dismissNotice()
            return "notice-dismissed"
        }
        hidden = !hidden
        return if (hidden) "hud-hidden" else "hud-shown"
    }
}
