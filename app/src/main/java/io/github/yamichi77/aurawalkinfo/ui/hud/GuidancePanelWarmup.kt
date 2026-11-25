package io.github.yamichi77.aurawalkinfo.ui.hud

import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

internal data class GuidancePanelSlots(val skyCount: Int = 0, val navReady: Boolean = false)

@Composable
internal fun rememberGuidancePanelSlots(
    spec: HudVisualSpec,
    quantum: HudQuantum,
    locationDenied: Boolean,
    bigMapOpen: Boolean,
    skyFrame: State<SkyBandFrame>,
): GuidancePanelSlots {
    var slots by remember { mutableStateOf(GuidancePanelSlots()) }
    if (slots.navReady && slots.skyCount == SkyPanelSlots.size) return slots

    val clusters = spec as? HudVisualSpec.Clusters
    // Keep background creation out of the route preview and its Start interaction.
    val canWarmUp = clusters != null && !clusters.showSafetyNotice && !locationDenied &&
        clusters.destinationPrompt !is DestinationPrompt.Preview &&
        quantum.left?.effect != QuantumEffect.Startup && quantum.miniMap?.effect != QuantumEffect.Startup
    val skyNeeded = slots.skyCount < SkyPanelSlots.size && remember(skyFrame) {
        derivedStateOf { skyFrame.value.status == SkyBandStatus.SHOW }
    }.value
    val needsNavPanel = !slots.navReady && !locationDenied && !bigMapOpen && clusters?.navigation != null
    LaunchedEffect(canWarmUp, skyNeeded, needsNavPanel) {
        if (!canWarmUp && !skyNeeded && !needsNavPanel) return@LaunchedEffect
        if (!skyNeeded && !needsNavPanel) {
            val settle = QuantumFrameSettle()
            while (!settle.onFrame(withFrameNanos { it })) Unit
        }
        while (true) {
            val next = when {
                needsNavPanel && !slots.navReady -> slots.copy(navReady = true)
                skyNeeded && slots.skyCount < SkyPanelSlots.size -> slots.copy(skyCount = slots.skyCount + 1)
                canWarmUp && !slots.navReady -> slots.copy(navReady = true)
                canWarmUp && slots.skyCount < SkyPanelSlots.size -> slots.copy(skyCount = slots.skyCount + 1)
                else -> break
            }
            // This clock belongs to the composition that mounts both kinds of SpatialPanel.
            withFrameNanos { slots = next }
        }
    }
    return slots
}

internal inline fun <T> traceHud(section: String, block: () -> T): T {
    Trace.beginSection(section)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
