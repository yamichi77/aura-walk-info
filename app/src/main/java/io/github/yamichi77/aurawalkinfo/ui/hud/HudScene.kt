package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
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
import androidx.xr.compose.subspace.draw.alpha
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
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
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import io.github.yamichi77.aurawalkinfo.safety.HeadMotionGate
import io.github.yamichi77.aurawalkinfo.safety.HideHudOnHeadMotion
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import java.util.Locale

private const val HeadMotionTag = "AuraHeadMotion"

@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
internal fun HudScene(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    debug: HudDebugOptions,
    onNoticeTimeout: () -> Unit,
    bigMapOpen: Boolean,
    quantumStartup: HudQuantumStartup,
    wallClock: WallClock,
    onTap: (HudTarget) -> Unit,
) {
    val session = LocalSession.current
    if (session == null) {
        PhoneScreen(
            spec, locationDenied, debug, onNoticeTimeout, bigMapOpen,
            quantumStartup = quantumStartup, wallClock = wallClock, onTap = onTap,
        )
        return
    }
    val debugBuild = LocalContext.current.isDebugBuild()

    var configured by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        val config = Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()
        configured = session.config.deviceTracking == DeviceTrackingMode.SPATIAL ||
            session.configure(config) is SessionConfigureSuccess
        if (!configured) {
            Log.w(HeadMotionTag, "tracking unavailable: SPATIAL configuration failed; motion gate open")
            if (debug.selfCheck) Log.i("AuraPlacement", "placement FAIL SPATIAL configuration unavailable")
        }
    }

    val check = remember(session, debug.selfCheck, debug.grid) {
        if (debug.selfCheck) HudPlacementCheck(debug.grid) else null
    }
    var showRotationFallback by remember(check) { mutableStateOf(false) }
    var tracking by remember(session) { mutableStateOf(false) }
    var hiddenForMotion by remember(session) { mutableStateOf(false) }
    val checkBigMap by rememberUpdatedState(bigMapOpen && spec is HudVisualSpec.Clusters)
    LaunchedEffect(session, configured, check) {
        if (!configured) return@LaunchedEffect
        val device = ArDevice.getInstance(session)
        val gate = HeadMotionGate()
        var wasTracking: Boolean? = null
        var peakSpeed = 0.0
        var lastSpeedLog = 0L
        var activeCheck = check
        var bigMapReported = false
        while (true) {
            withFrameNanos { now ->
                val state = device.state.value
                val pose = state.devicePose.takeIf { state.trackingState == TrackingState.TRACKING }
                tracking = pose != null
                if (tracking && showRotationFallback) showRotationFallback = false
                if (!tracking && wasTracking != false) {
                    Log.w(HeadMotionTag, "tracking unavailable: ${state.trackingState}; motion gate open")
                }
                wasTracking = tracking
                val rotation = pose?.rotation?.let {
                    HeadMotionGate.Rotation(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.w.toDouble())
                }
                val speed = gate.update(now, rotation)
                val hidden = gate.hiddenSinceNanos != null
                if (debugBuild) {
                    if (speed != null) peakSpeed = maxOf(peakSpeed, speed)
                    if (hidden != hiddenForMotion) {
                        Log.i(HeadMotionTag, "${if (hidden) "HIDE" else "SHOW"} ${formatHeadSpeed(speed, peakSpeed)}")
                    }
                    if (now - lastSpeedLog >= 1_000_000_000L) {
                        Log.i(HeadMotionTag, "SPEED ${formatHeadSpeed(speed, peakSpeed)}")
                        lastSpeedLog = now
                    }
                }
                hiddenForMotion = hidden
                activeCheck?.sample(session, now, pose, speed, gate.hiddenSinceNanos)?.let { fallback ->
                    showRotationFallback = fallback
                    activeCheck = null
                }
                if (!checkBigMap) bigMapReported = false
                if (checkBigMap && !bigMapReported && pose != null && speed != null &&
                    speed < HeadMotionGate.StationaryDegreesPerSecond
                ) {
                    bigMapReported = check?.sampleBigMap(session, pose) == true
                }
            }
        }
    }
    val motionOpacity = if (HideHudOnHeadMotion) animateFloatAsState(
        if (hiddenForMotion) 0f else 1f,
        tween(if (hiddenForMotion) HeadMotionGate.HideMillis else HeadMotionGate.ShowMillis),
    ) else null

    var scale by remember(session) { mutableStateOf<Float?>(null) }
    val ready = configured && tracking && scale != null
    val countdown = rememberCountdownPresentation(if (ready) spec else HudVisualSpec.Hidden)
    val quantum = rememberHudQuantum(spec, locationDenied, bigMapOpen, countdown, quantumStartup, ready)
    if (!configured || spec is HudVisualSpec.Hidden) return
    Subspace(follow = FollowTarget.view(FollowMode.tight(TrackedDimensions.All))) {
        // Read the scale applied by Compose XR in the actual following hierarchy.
        var root by remember { mutableStateOf<Entity?>(null) }
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
                        session, it, spec, locationDenied, debug, check, motionOpacity?.value ?: 1f, tracking,
                        showRotationFallback, onNoticeTimeout, bigMapOpen, onTap,
                        !HideHudOnHeadMotion || !hiddenForMotion, countdown, quantum, wallClock,
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
    motionOpacity: Float,
    tracking: Boolean,
    showRotationFallback: Boolean,
    onNoticeTimeout: () -> Unit,
    bigMapOpen: Boolean,
    onTap: (HudTarget) -> Unit,
    inputEnabled: Boolean,
    countdown: CountdownPresentation?,
    quantum: HudQuantum,
    wallClock: WallClock,
) {
    val pixelDensity = session.scene.virtualPixelDensity
    val dpPerMeter = pixelDensity.convertMetersToPixels(1f) / LocalDensity.current.density
    val physicalDpPerMeter = dpPerMeter / scale
    val mapVisible = spec is HudVisualSpec.Clusters && !locationDenied
    val showBigMap = bigMapOpen && mapVisible
    val bigMapQuantum = quantum.bigMap
    val bigMapAlpha = if (mapVisible && bigMapQuantum == null) {
        animateFloatAsState(if (showBigMap) 1f else 0f, tween(HudAnimationMillis)).value
    } else 1f.takeIf { bigMapQuantum != null } ?: 0f

    if (tracking) {
        if (showBigMap && inputEnabled) {
            val distance = 1.65f
            SpatialPanel(
                HudPlacement.modifier(HudPlacement.pose(0f, 0f, distance), dpPerMeter, scale)
                    .width((2 * distance / Layout.distanceMeters * HudPlacement.planeHalfSpan(32f) * physicalDpPerMeter).dp)
                    .height((2 * distance / Layout.distanceMeters * HudPlacement.planeHalfSpan(22f) * physicalDpPerMeter).dp),
                shape = SpatialRoundedCornerShape(CornerSize(0.dp)),
            ) {
                Box(Modifier.fillMaxSize().hudClick(HudTarget.OUTSIDE, onTap))
            }
        }
        PlacedPanel(
            session, HudPanel.LEFT, HudPlacement.statsWidthMeters, HudPlacement.statsHeightMeters,
            dpPerMeter, scale, check, motionOpacity,
        ) {
            StatsPanel(
                spec, locationDenied, Layout.textScale(physicalDpPerMeter),
                Modifier.fillMaxSize().clipToBounds().hudClick(HudTarget.LEFT, onTap, inputEnabled)
                    .capturePanelOnNextDraw(debug.leftCaptureName("xr", spec)),
                onNoticeTimeout,
                quantum.left,
                wallClock,
            )
        }

        if (countdown != null) {
            SpatialPanel(
                HudPlacement.modifier(HudPlacement.pose(0f, 0f), dpPerMeter, scale)
                    .width((HudPlacement.countdownWidthMeters * physicalDpPerMeter).dp)
                    .height((HudPlacement.countdownHeightMeters * physicalDpPerMeter).dp)
                    .alpha(motionOpacity),
            ) {
                CountdownPanel(countdown, physicalDpPerMeter, onTap, Modifier.fillMaxSize(), inputEnabled)
            }
        }
        if (spec is HudVisualSpec.Clusters && !locationDenied && !showBigMap) {
            val diameter by animateFloatAsState(spec.mapDiameter, tween(HudAnimationMillis))
            val size = HudPlacement.mapSizeMeters(diameter)
            PlacedPanel(session, HudPanel.RIGHT, size, size, dpPerMeter, scale, check, motionOpacity) {
                QuantumMap(
                    quantum.miniMap, large = false,
                    Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "right")),
                ) { mapModifier ->
                    ClickableMap(spec, debug, HudTarget.MAP, onTap, mapModifier, inputEnabled)
                }
            }
        } else if (debug.capture && spec is HudVisualSpec.Fast) {
            PlacedPanel(session, HudPanel.RIGHT, 0.1f, 0.1f, dpPerMeter, scale, null, motionOpacity) {
                Box(Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "right")))
            }
        }
        if (spec is HudVisualSpec.Clusters && !locationDenied &&
            (showBigMap || bigMapAlpha > 0f || bigMapQuantum != null)
        ) {
            val size = HudPlacement.bigMapSizeMeters
            PlacedPanel(
                session, HudPanel.BIGMAP, size, size, dpPerMeter, scale, check,
                motionOpacity * bigMapAlpha,
            ) {
                QuantumMap(bigMapQuantum, large = true, Modifier.fillMaxSize()) { mapModifier ->
                    ClickableMap(
                        spec, debug, HudTarget.BIGMAP, onTap, mapModifier, showBigMap && inputEnabled,
                        bigMapArrowHeight = (HudPlacement.bigMapArrowHeightMeters * physicalDpPerMeter).dp,
                    )
                }
            }
        }
    }

    if (spec !is HudVisualSpec.Fast && debug.grid && !showBigMap && (tracking || showRotationFallback)) {
        PlacedPanel(session, HudPanel.GRID, gridWidthMeters, gridHeightMeters, dpPerMeter, scale, check) {
            DebugAngleGrid(Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "grid")))
        }
    }

    if (spec !is HudVisualSpec.Fast && showRotationFallback) {
        for ((index, pitch) in listOf(2f, -2f).withIndex()) {
            val pose = HudPlacement.pose(Layout.leftYaw, pitch)
            val facing = if (index == 0) pose.rotation else HudPlacement.rotation(-Layout.leftYaw, pitch)
            SpatialPanel(
                HudPlacement.modifier(Pose(pose.translation, facing), dpPerMeter, scale)
                    .alpha(motionOpacity)
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
    motionOpacity: Float = 1f,
    content: @Composable () -> Unit,
) {
    val pose = remember(name) {
        if (name == HudPanel.LEFT) HudPlacement.statsPose() else HudPlacement.pose(name.yaw, name.pitch)
    }
    val placement = HudPlacement.modifier(pose, dpPerMeter, scale).alpha(motionOpacity)
    val size = SubspaceModifier.width((width * dpPerMeter / scale).dp)
        .height((height * dpPerMeter / scale).dp)
    val shape = if (name == HudPanel.BIGMAP) SpatialRoundedCornerShape(CornerSize(50)) else null
    if (check == null) {
        if (shape == null) SpatialPanel(placement.then(size), content = content)
        else SpatialPanel(placement.then(size), shape = shape, content = content)
    } else {
        // The wrapper and its sole, centered SpatialPanel share a center and normal.
        var entity by remember { mutableStateOf<Entity?>(null) }
        val pixels = session.scene.virtualPixelDensity
        SceneCoreEntity(factory = { Entity.create(session, "Aura ${name.name}") }, modifier = placement, update = { entity = it }) {
            val reference = entity
            DisposableEffect(reference, check, name) {
                onDispose { check.panels.remove(name) }
            }
            val measuredSize = size.onGloballyPositioned { coordinates ->
                    if (reference != null) {
                        check.panels[name] = HudPlacementCheck.Panel(
                            reference,
                            pixels.convertPixelsToMeters(coordinates.size.width.toFloat()) * scale,
                            pixels.convertPixelsToMeters(coordinates.size.height.toFloat()) * scale,
                        )
                    }
                }
            if (shape == null) SpatialPanel(measuredSize, content = content)
            else SpatialPanel(measuredSize, shape = shape, content = content)
        }
    }
}

private fun formatHeadSpeed(speed: Double?, peak: Double): String {
    val current = speed?.let { "%.2f".format(Locale.ROOT, it) } ?: "unavailable"
    return "speed=$current peak=${"%.2f".format(Locale.ROOT, peak)}"
}
