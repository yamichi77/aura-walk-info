package io.github.yamichi77.aurawalkinfo.safety

import android.content.Context

class SafetyNotice(context: Context) {
    private val preferences = context.getSharedPreferences("safety_notice", Context.MODE_PRIVATE)

    val hasBeenShown: Boolean get() = preferences.getBoolean(SHOWN_KEY, false)

    fun markShown() {
        preferences.edit().putBoolean(SHOWN_KEY, true).apply()
    }

    companion object {
        const val MESSAGE = "徒歩専用。自転車・運転中は使用不可。横断歩道や駅のホームでは周囲を優先"
        private const val SHOWN_KEY = "shown"
    }
}
