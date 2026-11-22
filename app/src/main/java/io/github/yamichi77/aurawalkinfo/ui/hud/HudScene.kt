package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.animation.follow.FollowMode
import androidx.xr.compose.subspace.animation.follow.FollowTarget
import androidx.xr.compose.subspace.animation.follow.TrackedDimensions
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.onGloballyPositioned
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.HudPanel
import io.github.yamichi77.aurawalkinfo.diagnostics.HudPlacementCheck
import io.github.yamichi77.aurawalkinfo.diagnostics.capturePanelOnNextDraw
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
internal fun HudScene(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    debug: HudDebugOptions,
    onNoticeTimeout: () -> Unit,
) {
    val session = LocalSession.current
    if (session == null) {
        PhoneScreen(spec, locationDenied, debug, onNoticeTimeout)
        return
    }

    var configured by remember(session) { mutableStateOf(false) }
    var tracking by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        val config = Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()
        configured = session.config.deviceTracking == DeviceTrackingMode.SPATIAL ||
            session.configure(config) is SessionConfigureSuccess
        if (configured) {
            ArDevice.getInstance(session).state.map { it.trackingState == TrackingState.TRACKING }
                .distinctUntilChanged().collect { tracking = it }
        } else if (debug.selfCheck) {
            Log.i("AuraPlacement", "placement FAIL SPATIAL configuration unavailable")
        }
    }

    val check = remember(session, debug.selfCheck) {
        if (debug.selfCheck) HudPlacementCheck() else null
    }
    var showRotationFallback by remember(session) { mutableStateOf(false) }
    LaunchedEffect(check, debug.grid) {
        if (check != null) {
            val expected = listOf(HudPanel.LEFT, HudPanel.RIGHT) + if (debug.grid) listOf(HudPanel.GRID) else emptyList()
            showRotationFallback = check.run(session, expected)
        }
    }

    if (!configured || spec is HudVisualSpec.Hidden) return
    Subspace(follow = FollowTarget.view(FollowMode.tight(TrackedDimensions.All))) {
        // Read the scale applied by Compose XR in the actual following hierarchy.
        var root by remember { mutableStateOf<Entity?>(null) }
        var scale by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(root) {
            val entity = root ?: return@LaunchedEffect
            while (true) {
                withFrameNanos { scale = entity.getScale(Space.ACTIVITY).takeIf { it > 0f } }
            }
        }
        SceneCoreEntity(factory = { Entity.create(session, "Aura HUD") }, update = { root = it }) {
            scale?.let {
                if (tracking || showRotationFallback) {
                    SpatialHudPanels(
                        session, it, spec, locationDenied, debug, check, tracking, showRotationFallback, onNoticeTimeout,
                    )
                }
            }
        }
    }
}

@Composable
@SubspaceComposable
private fun SpatialHudPanels(
    session: Session,
    scale: Float,
    spec: HudVisualSpec,
    locationDenied: Boolean,
    debug: HudDebugOptions,
    check: HudPlacementCheck?,
    tracking: Boolean,
    showRotationFallback: Boolean,
    onNoticeTimeout: () -> Unit,
) {
    val pixelDensity = session.scene.virtualPixelDensity
    val dpPerMeter = pixelDensity.convertMetersToPixels(1f) / LocalDensity.current.density
    val physicalDpPerMeter = dpPerMeter / scale

    if (tracking) {
        PlacedPanel(
            session, HudPanel.LEFT, HudPlacement.statsWidthMeters, HudPlacement.statsHeightMeters,
            dpPerMeter, scale, check,
        ) {
            StatsPanel(
                spec, locationDenied, Layout.textScale(physicalDpPerMeter),
                Modifier.fillMaxSize().clipToBounds().capturePanelOnNextDraw(debug.leftCaptureName("xr", spec)),
                onNoticeTimeout,
            )
        }

        if (spec is HudVisualSpec.Clusters && !locationDenied) {
            val diameter by animateFloatAsState(spec.mapDiameter, tween(HudAnimationMillis))
            val size = HudPlacement.mapSizeMeters(diameter)
            PlacedPanel(session, HudPanel.RIGHT, size, size, dpPerMeter, scale, check) {
                MiniMapPanel(
                    spec, debug, Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "right")),
                )
            }
        } else if (debug.capture && spec is HudVisualSpec.Fast) {
            PlacedPanel(session, HudPanel.RIGHT, 0.1f, 0.1f, dpPerMeter, scale, null) {
                Box(Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "right")))
            }
        }
    }

    if (debug.grid && (tracking || showRotationFallback)) {
        PlacedPanel(session, HudPanel.GRID, gridWidthMeters, gridHeightMeters, dpPerMeter, scale, check) {
            DebugAngleGrid(Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "grid")))
        }
    }

    if (showRotationFallback) {
        for ((index, pitch) in listOf(2f, -2f).withIndex()) {
            val pose = HudPlacement.pose(Layout.leftYaw, pitch)
            val facing = if (index == 0) pose.rotation else HudPlacement.rotation(-Layout.leftYaw, pitch)
            SpatialPanel(
                HudPlacement.modifier(Pose(pose.translation, facing), dpPerMeter, scale)
                    .width((0.07f * physicalDpPerMeter).dp)
                    .height((0.07f * physicalDpPerMeter).dp),
            ) {
                Box(Modifier.fillMaxSize().border(1.dp, HudText), contentAlignment = Alignment.Center) {
                    Text(if (index == 0) "A" else "B", color = HudText)
                }
            }
        }
    }
}

@Composable
@SubspaceComposable
private fun PlacedPanel(
    session: Session,
    name: HudPanel,
    width: Float,
    height: Float,
    dpPerMeter: Float,
    scale: Float,
    check: HudPlacementCheck?,
    content: @Composable () -> Unit,
) {
    val pose = remember(name) { HudPlacement.pose(name.yaw, name.pitch) }
    val placement = HudPlacement.modifier(pose, dpPerMeter, scale)
    val size = SubspaceModifier.width((width * dpPerMeter / scale).dp)
        .height((height * dpPerMeter / scale).dp)
    if (check == null) {
        SpatialPanel(placement.then(size), content = content)
    } else {
        // The wrapper and its sole, centered SpatialPanel share a center and normal.
        var entity by remember { mutableStateOf<Entity?>(null) }
        val pixels = session.scene.virtualPixelDensity
        SceneCoreEntity(factory = { Entity.create(session, "Aura ${name.name}") }, modifier = placement, update = { entity = it }) {
            val reference = entity
            DisposableEffect(reference, check, name) {
                onDispose { check.panels.remove(name) }
            }
            SpatialPanel(
                size.onGloballyPositioned { coordinates ->
                    if (reference != null) {
                        check.panels[name] = HudPlacementCheck.Panel(
                            reference,
                            pixels.convertPixelsToMeters(coordinates.size.width.toFloat()) * scale,
                            pixels.convertPixelsToMeters(coordinates.size.height.toFloat()) * scale,
                        )
                    }
                },
                content = content,
            )
        }
    }
}
