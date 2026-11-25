package io.github.yamichi77.aurawalkinfo.ui.fallback

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.capturePanelOnNextDraw
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.hud.BigMapButton
import io.github.yamichi77.aurawalkinfo.ui.hud.BigMapMode
import io.github.yamichi77.aurawalkinfo.ui.hud.BigMapViewport
import io.github.yamichi77.aurawalkinfo.ui.hud.CountdownPanel
import io.github.yamichi77.aurawalkinfo.ui.hud.DebugAngleGrid
import io.github.yamichi77.aurawalkinfo.ui.hud.HudAnimationMillis
import io.github.yamichi77.aurawalkinfo.ui.hud.HudPlacement
import io.github.yamichi77.aurawalkinfo.ui.hud.HudQuantumStartup
import io.github.yamichi77.aurawalkinfo.ui.hud.HudTarget
import io.github.yamichi77.aurawalkinfo.ui.hud.HudVisualSpec
import io.github.yamichi77.aurawalkinfo.ui.hud.NavGuidancePanel
import io.github.yamichi77.aurawalkinfo.ui.hud.QuantumEffectsEnabled
import io.github.yamichi77.aurawalkinfo.ui.hud.SharedMap
import io.github.yamichi77.aurawalkinfo.ui.hud.StatsPanel
import io.github.yamichi77.aurawalkinfo.ui.hud.buttonLabel
import io.github.yamichi77.aurawalkinfo.ui.hud.gridHeightMeters
import io.github.yamichi77.aurawalkinfo.ui.hud.gridWidthMeters
import io.github.yamichi77.aurawalkinfo.ui.hud.hudClick
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberBigMapButtons
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberBigMapDestinationLabel
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberCountdownPresentation
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberHudQuantum
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberMapSpec
import io.github.yamichi77.aurawalkinfo.ui.hud.rememberSharedMapPresentation
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout

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
    bigMapMode: BigMapMode = BigMapMode.FLAT,
    viewport: BigMapViewport? = null,
    onMapPan: () -> Unit = {},
    onMapHold: (NavPoint) -> Unit = {},
    onTap: (HudTarget) -> Unit,
) {
    val mapSpec = rememberMapSpec(spec, locationDenied)
    val mapVisible = !locationDenied && spec is HudVisualSpec.Clusters
    val showBigMap = bigMapOpen && mapVisible
    val map = rememberSharedMapPresentation(showBigMap, mapVisible, quantumEnabled)
    val buttonsAlpha = map.buttonsAlpha(quantumEnabled)
    val buttonsOpen = showBigMap && map.large && !map.hidden
    val countdown = rememberCountdownPresentation(spec, quantumEnabled)
    val quantum = rememberHudQuantum(
        spec, locationDenied, showBigMap, countdown, quantumStartup, enabled = quantumEnabled,
    )
    if (spec is HudVisualSpec.Hidden) return
    LaunchedEffect(debug) {
        if (debug.selfCheck) Log.i("AuraPlacement", "placement FAIL spatial UI unavailable")
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val preview = debug.fakeState != null || debug.grid || debug.capture
        val dpPerMeter = minOf(1_000f, maxWidth.value / 0.68f)
        Box(if (showBigMap) Modifier.matchParentSize().hudClick(HudTarget.OUTSIDE, onTap) else Modifier)
        Column(
            if (showBigMap) Modifier else Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (preview || mapVisible) {
                StatsPanel(
                    spec, locationDenied, if (preview) Layout.textScale(dpPerMeter) else 1f,
                    Modifier.size(
                        (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                        (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                    ).hudClick(HudTarget.LEFT, onTap).capturePanelOnNextDraw(debug.leftCaptureName("2d", spec)),
                    onNoticeTimeout, quantum.left, wallClock,
                )
            } else {
                StatsPanel(spec, locationDenied, 1f, Modifier.hudClick(HudTarget.LEFT, onTap), onNoticeTimeout)
            }
            if (debug.grid && spec !is HudVisualSpec.Fast) DebugAngleGrid(
                Modifier.fillMaxWidth().aspectRatio(gridWidthMeters / gridHeightMeters)
                    .capturePanelOnNextDraw(debug.captureName("2d", "grid")),
            )
        }
        if (mapSpec != null) {
            val buttonWidth = minOf(144.dp, maxWidth / 3)
            val gap = 4.dp
            val mapSize = minOf(maxHeight * 0.85f, maxWidth - buttonWidth - gap)
            val miniDiameter by animateFloatAsState(mapSpec.mapDiameter, tween(HudAnimationMillis))
            val miniSize = minOf(mapSize, (HudPlacement.mapSizeMeters(miniDiameter) * dpPerMeter).dp)
            val miniScale = miniSize / mapSize
            val scale = if (map.large) 1f else miniScale
            val largeX = (maxWidth - mapSize - gap - buttonWidth) / 2
            val largeY = (maxHeight - mapSize) / 2
            val miniX = maxWidth - miniSize
            val miniY = maxHeight - miniSize
            if (mapVisible && map.settledMini && !showBigMap) mapSpec.navigation?.let { visual ->
                NavGuidancePanel(visual, mapSpec.opacity, Modifier.align(Alignment.BottomEnd)
                    .offset(y = -miniSize).width((HudPlacement.navWidthMeters * dpPerMeter).dp))
            }
            SharedMap(mapSpec, debug, map, mapVisible, showBigMap, miniScale, onTap,
                Modifier.offset(if (map.large) largeX else miniX, if (map.large) largeY else miniY)
                    .size(mapSize).graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        alpha = if (mapVisible && !map.hidden) 1f else 0f
                    // Keep the AndroidView measured but unplaced while fast.
                    }.then(if (mapVisible) Modifier else Modifier.layout { measurable, constraints ->
                        measurable.measure(constraints)
                        layout(0, 0) {}
                    }).capturePanelOnNextDraw(debug.captureName("2d", "right").takeIf { mapVisible && map.settledMini }),
                mapVisible, (HudPlacement.bigMapArrowHeightMeters * dpPerMeter).dp, bigMapMode,
                viewport, onMapPan, onMapHold, quantum, quantumEnabled)
            val destinationLabel = rememberBigMapDestinationLabel(mapSpec, showBigMap)
            val buttons = rememberBigMapButtons(mapSpec.destinationPrompt, showBigMap)
            val diameter = mapSize * (HudPlacement.bigMapButtonDiameterMeters / HudPlacement.bigMapSizeMeters)
            val buttonGap = 2.dp
            val count = HudPlacement.bigMapButtons.size
            val height = minOf((maxHeight - buttonGap * (count - 1)) / count, diameter + 36.dp)
            Column(Modifier.offset(x = largeX + mapSize + gap,
                y = (maxHeight - height * count - buttonGap * (count - 1)) / 2).alpha(buttonsAlpha),
                verticalArrangement = Arrangement.spacedBy(buttonGap)) {
                repeat(count) { index ->
                    Box(Modifier.size(buttonWidth, height)) {
                        val button = buttons.getOrNull(index)
                        if (buttonsAlpha > 0f && button != null) {
                            BigMapButton(button.buttonLabel(destinationLabel, bigMapMode), button.target,
                                mapSpec.opacity, map.animation, buttonsOpen,
                                buttonsOpen && map.acceptsLargeInput, quantumEnabled, diameter,
                                onTap, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        if (spec is HudVisualSpec.Fast && debug.capture) {
            Box(Modifier.align(Alignment.BottomEnd).size(64.dp)
                .capturePanelOnNextDraw(debug.captureName("2d", "right")))
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
