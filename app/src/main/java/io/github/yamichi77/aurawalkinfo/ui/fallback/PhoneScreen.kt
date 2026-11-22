package io.github.yamichi77.aurawalkinfo.ui.fallback

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.capturePanelOnNextDraw
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.hud.DebugAngleGrid
import io.github.yamichi77.aurawalkinfo.ui.hud.HudPlacement
import io.github.yamichi77.aurawalkinfo.ui.hud.HudVisualSpec
import io.github.yamichi77.aurawalkinfo.ui.hud.MiniMapPlaceholder
import io.github.yamichi77.aurawalkinfo.ui.hud.StatsPanel
import io.github.yamichi77.aurawalkinfo.ui.hud.gridHeightMeters
import io.github.yamichi77.aurawalkinfo.ui.hud.gridWidthMeters

@Composable
internal fun PhoneScreen(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    debug: HudDebugOptions,
    onNoticeTimeout: () -> Unit,
) {
    if (spec is HudVisualSpec.Hidden) return
    LaunchedEffect(debug) {
        if (debug.selfCheck) Log.i("AuraPlacement", "placement FAIL spatial UI unavailable")
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
        val preview = debug.fakeState != null || debug.grid || debug.capture
        val dpPerMeter = minOf(1_000f, maxWidth.value / 0.68f)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    StatsPanel(
                        spec, locationDenied, Layout.textScale(dpPerMeter),
                        Modifier.size(
                            (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                            (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                        ).capturePanelOnNextDraw(debug.leftCaptureName("2d", spec)),
                        onNoticeTimeout,
                    )
                    if (!locationDenied && spec is HudVisualSpec.Clusters) {
                        val size = (HudPlacement.mapSizeMeters(spec.mapDiameter) * dpPerMeter).dp
                        MiniMapPlaceholder(
                            spec, Modifier.size(size).capturePanelOnNextDraw(debug.captureName("2d", "right")),
                        )
                    } else if (spec is HudVisualSpec.Fast && debug.capture) {
                        // Capture a transparent reference showing that the right cluster was removed.
                        Box(Modifier.size(64.dp).capturePanelOnNextDraw(debug.captureName("2d", "right")))
                    }
                }
            } else {
                StatsPanel(spec, locationDenied, 1f, onNoticeTimeout = onNoticeTimeout)
            }
            if (debug.grid) DebugAngleGrid(
                Modifier.fillMaxWidth().aspectRatio(gridWidthMeters / gridHeightMeters)
                    .capturePanelOnNextDraw(debug.captureName("2d", "grid")),
            )
        }
    }
}
