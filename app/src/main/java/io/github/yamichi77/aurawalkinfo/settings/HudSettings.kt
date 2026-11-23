package io.github.yamichi77.aurawalkinfo.settings

import android.content.Context

internal class HudSettings(context: Context) {
    private val preferences = context.getSharedPreferences("hud_settings", Context.MODE_PRIVATE)

    var bigMap3d: Boolean
        get() = preferences.getBoolean("big_map_3d", false)
        set(value) {
            preferences.edit().putBoolean("big_map_3d", value).apply()
        }
}
