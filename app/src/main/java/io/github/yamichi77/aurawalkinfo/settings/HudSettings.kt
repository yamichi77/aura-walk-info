package io.github.yamichi77.aurawalkinfo.settings

import android.content.Context

internal class HudSettings(context: Context) {
    private val preferences = context.getSharedPreferences("hud_settings", Context.MODE_PRIVATE)

    var geospatialConsent: Boolean?
        get() = preferences.takeIf { it.contains("geospatial_consent") }?.getBoolean("geospatial_consent", false)
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove("geospatial_consent") else editor.putBoolean("geospatial_consent", value)
            editor.apply()
        }

    var skyBand: Boolean
        get() = preferences.getBoolean("sky_band", true)
        set(value) {
            preferences.edit().putBoolean("sky_band", value).apply()
        }

    var bigMap3d: Boolean
        get() = preferences.getBoolean("big_map_3d", false)
        set(value) {
            preferences.edit().putBoolean("big_map_3d", value).apply()
        }

    var spatialMarks: Boolean
        get() = preferences.getBoolean("spatial_marks", true)
        set(value) {
            preferences.edit().putBoolean("spatial_marks", value).apply()
        }
}
