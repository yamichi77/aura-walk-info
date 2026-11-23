package io.github.yamichi77.aurawalkinfo.diagnostics

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import io.github.yamichi77.aurawalkinfo.settings.HudSettings

internal class SkyBandSettings(private val settings: HudSettings) {
    var enabled by mutableStateOf(settings.skyBand)
        private set
    var northOverride by mutableStateOf<NorthEstimate?>(null)
        private set
    var simulateTrackingLoss by mutableStateOf(false)
        private set

    fun applyDebugIntent(intent: Intent) {
        if (intent.hasExtra("sky_band")) {
            enabled = intent.getBooleanExtra("sky_band", enabled)
            settings.skyBand = enabled
        }
        if (intent.hasExtra("sky_tracking_lost")) {
            simulateTrackingLoss = intent.getBooleanExtra("sky_tracking_lost", false)
        }
        if (intent.getBooleanExtra("clear_north", false)) {
            northOverride = null
        } else if (intent.hasExtra("north_offset") && intent.hasExtra("north_uncertainty")) {
            val offset = intent.getFloatExtra("north_offset", Float.NaN).toDouble()
            val uncertainty = intent.getFloatExtra("north_uncertainty", Float.NaN).toDouble()
            if (offset.isFinite() && uncertainty.isFinite() && uncertainty >= 0) {
                northOverride = NorthEstimate(offset, uncertainty)
            }
        }
    }
}

@Composable
internal fun rememberSkyBandSettings(settings: HudSettings): SkyBandSettings {
    val context = LocalContext.current
    val state = remember(settings) { SkyBandSettings(settings) }
    DisposableEffect(context, state) {
        if (!context.isDebugBuild()) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = state.applyDebugIntent(intent)
        }
        context.registerReceiver(
            receiver, IntentFilter("io.github.yamichi77.aurawalkinfo.SETTINGS"),
            Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED,
        )
        Log.i("AuraSky", "SETTINGS ready enabled=${state.enabled}")
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}
