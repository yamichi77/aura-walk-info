package io.github.yamichi77.aurawalkinfo.ui.fallback

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.capturePanelOnNextDraw
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.hud.DebugAngleGrid
import io.github.yamichi77.aurawalkinfo.ui.hud.ClickableMap
import io.github.yamichi77.aurawalkinfo.ui.hud.CountdownPanel
import io.github.yamichi77.aurawalkinfo.ui.hud.HudQuantumStartup
import io.github.yamichi77.aurawalkinfo.ui.hud.HudPlacement
import io.github.yamichi77.aurawalkinfo.ui.hud.HudVisualSpec
import io.github.yamichi77.aurawalkinfo.ui.hud.HudAnimationMillis
import io.github.yamichi77.aurawalkinfo.ui.hud.HudTarget
import io.github.yamichi77.aurawalkinfo.ui.hud.QuantumEffectsEnabled
import io.github.yamichi77.aurawalkinfo.ui.hud.QuantumMap
import io.github.yamichi77.aurawalkinfo.ui.hud.StatsPanel
import io.github.yamichi77.aurawalkinfo.ui.hud.gridHeightMeters
import io.github.yamichi77.aurawalkinfo.ui.hud.gridWidthMeters
import io.github.yamichi77.aurawalkinfo.ui.hud.hudClick
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberCountdownPresentation
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberHudQuantum

@Composable
internal fun PhoneScreen(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    debug: HudDebugOptions,
    onNoticeTimeout: () -> Unit,
    bigMapOpen: Boolean,
    quantumEnabled: Boolean = QuantumEffectsEnabled,
    quantumStartup: HudQuantumStartup = remember { HudQuantumStartup() },
    wallClock: WallClock? = null,
    onTap: (HudTarget) -> Unit,
) {
    val allMapsVisible = !locationDenied && spec is HudVisualSpec.Clusters
    val showBigMap = bigMapOpen && allMapsVisible
    val countdown = rememberCountdownPresentation(spec, quantumEnabled)
    val quantum = rememberHudQuantum(
        spec, locationDenied, showBigMap, countdown, quantumStartup, enabled = quantumEnabled,
    )
    if (spec is HudVisualSpec.Hidden) return
    LaunchedEffect(debug) {
        if (debug.selfCheck) Log.i("AuraPlacement", "placement FAIL spatial UI unavailable")
    }
    val bigMapQuantum = quantum.bigMap
    val bigMapAlpha = if (allMapsVisible && bigMapQuantum == null) {
        animateFloatAsState(if (showBigMap) 1f else 0f, tween(HudAnimationMillis)).value
    } else 0f
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val preview = debug.fakeState != null || debug.grid || debug.capture
        val dpPerMeter = minOf(1_000f, maxWidth.value / 0.68f)
        if (showBigMap) Box(Modifier.matchParentSize().hudClick(HudTarget.OUTSIDE, onTap))
        Column(
            if (showBigMap) Modifier else Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (preview || spec is HudVisualSpec.Clusters && !locationDenied) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    StatsPanel(
                        spec, locationDenied, if (preview) Layout.textScale(dpPerMeter) else 1f,
                        Modifier.size(
                            (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                            (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                        ).hudClick(HudTarget.LEFT, onTap).capturePanelOnNextDraw(debug.leftCaptureName("2d", spec)),
                        onNoticeTimeout,
                        quantum.left,
                        wallClock,
                    )
                    if (!locationDenied && spec is HudVisualSpec.Clusters && !showBigMap) {
                        val diameter by animateFloatAsState(spec.mapDiameter, tween(HudAnimationMillis))
                        val size = (HudPlacement.mapSizeMeters(diameter) * dpPerMeter).dp
                        QuantumMap(
                            quantum.miniMap, large = false,
                            Modifier.size(size).capturePanelOnNextDraw(debug.captureName("2d", "right")),
                        ) { mapModifier ->
                            ClickableMap(spec, debug, HudTarget.MAP, onTap, mapModifier)
                        }
                    } else if (spec is HudVisualSpec.Fast && debug.capture) {
                        // Capture a transparent reference showing that the right cluster was removed.
                        Box(Modifier.size(64.dp).capturePanelOnNextDraw(debug.captureName("2d", "right")))
                    }
                }
            } else {
                StatsPanel(spec, locationDenied, 1f, Modifier.hudClick(HudTarget.LEFT, onTap), onNoticeTimeout)
            }
            if (debug.grid && spec !is HudVisualSpec.Fast) DebugAngleGrid(
                Modifier.fillMaxWidth().aspectRatio(gridWidthMeters / gridHeightMeters)
                    .capturePanelOnNextDraw(debug.captureName("2d", "grid")),
            )
        }
        if (spec is HudVisualSpec.Clusters && !locationDenied &&
            (showBigMap || bigMapAlpha > 0f || bigMapQuantum != null)
        ) {
            val mapModifier = Modifier.align(Alignment.Center).size(minOf(maxWidth, maxHeight) * 0.85f)
            QuantumMap(
                bigMapQuantum, large = true,
                if (bigMapQuantum == null) mapModifier.alpha(bigMapAlpha) else mapModifier,
            ) { presentationModifier ->
                ClickableMap(
                    spec, debug, HudTarget.BIGMAP, onTap, presentationModifier,
                    enabled = showBigMap,
                    bigMapArrowHeight = (HudPlacement.bigMapArrowHeightMeters * dpPerMeter).dp,
                )
            }
        }
        countdown?.let {
            CountdownPanel(
                countdown, dpPerMeter, onTap,
                Modifier.align(Alignment.Center).size(
                    (HudPlacement.countdownWidthMeters * dpPerMeter).dp,
                    (HudPlacement.countdownHeightMeters * dpPerMeter).dp,
                ),
            )
        }
    }
}
