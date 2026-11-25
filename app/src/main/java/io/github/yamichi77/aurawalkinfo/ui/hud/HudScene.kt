package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialPanelDefaults
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.animation.follow.FollowMode
import androidx.xr.compose.subspace.animation.follow.FollowTarget
import androidx.xr.compose.subspace.animation.follow.FollowThresholds
import androidx.xr.compose.subspace.animation.follow.TrackedDimensions
import androidx.xr.compose.subspace.draw.alpha
import androidx.xr.compose.subspace.draw.scale
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
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
import io.github.yamichi77.aurawalkinfo.diagnostics.HudFrameStats
import io.github.yamichi77.aurawalkinfo.diagnostics.HudPanel
import io.github.yamichi77.aurawalkinfo.diagnostics.HudPlacementCheck
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.diagnostics.capturePanelOnNextDraw
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.safety.HeadMotionGate
import io.github.yamichi77.aurawalkinfo.safety.HideHudOnHeadMotion
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import java.util.Locale
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout

private const val HeadMotionTag = "AuraHeadMotion"
internal const val HudFollowHalfLifeMillis = 50L

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
    bigMapMode: BigMapMode,
    onTap: (HudTarget) -> Unit,
    skyBandSettings: SkyBandSettings,
    headingTracking: HeadingTracking,
    skyBandTargets: SkyBandTargets,
    showGeospatialConsent: Boolean = false,
    viewport: BigMapViewport,
    onMapPan: () -> Unit,
    onMapHold: (NavPoint) -> Unit,
    frameStats: HudFrameStats?,
) {
    val session = LocalSession.current
    if (session == null) {
        PhoneScreen(
            spec, locationDenied, debug, onNoticeTimeout, bigMapOpen,
            quantumStartup = quantumStartup, wallClock = wallClock, bigMapMode = bigMapMode, onTap = onTap,
            viewport = viewport, onMapPan = onMapPan, onMapHold = onMapHold,
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
                if (pose != null && activeCheck?.sample(session, now, pose, speed, gate.hiddenSinceNanos) != null) {
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
    val ready = configured && scale != null
    val countdown = rememberCountdownPresentation(if (ready) spec else HudVisualSpec.Hidden)
    val quantum = rememberHudQuantum(spec, locationDenied, bigMapOpen, countdown, quantumStartup, ready)
    val showing = ready && spec !is HudVisualSpec.Hidden
    LaunchedEffect(showing, tracking) {
        Log.i("AuraHud", "showing=$showing withoutTracking=${showing && !tracking}")
    }
    if (showing && frameStats != null) {
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(frameStats, owner) {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { frameStats.observeFrames() }
        }
    }
    if (!configured || spec is HudVisualSpec.Hidden) return
    // In rc01, the view target reads the initial ArDevice pose even before TRACKING.
    // tight is this soft mode with an 87 ms half-life. 1 ms snapped to each ArDevice pose and
    // juddered on AURA. Compared 87/50/25/10 ms on the device (2026-11-24); 50 ms was best.
    val followHalfLife = debug.followHalfLifeMillis ?: HudFollowHalfLifeMillis
    val follow = remember(followHalfLife) {
        Log.i("AuraHud", "follow halfLifeMillis=$followHalfLife")
        FollowTarget.view(
            FollowMode.soft(TrackedDimensions.All, halfLifeMillis = followHalfLife, startDelay = 0L,
                startThresholds = FollowThresholds.Zero),
        )
    }
    Subspace(follow = follow) {
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
                val dpPerMeter = session.scene.virtualPixelDensity.convertMetersToPixels(1f) /
                    LocalDensity.current.density
                val skyFrame = rememberSkyBandFrame(
                    skyBandTargets, skyBandSettings, headingTracking, tracking, spec is HudVisualSpec.Fast,
                    debugBuild,
                )
                val slots = rememberGuidancePanelSlots(
                    spec, quantum, locationDenied, bigMapOpen, skyFrame,
                )
                SpatialSkyBand(session, skyFrame, slots.skyCount, dpPerMeter, it, debug.selfCheck, frameStats)
                SpatialHudPanels(
                    dpPerMeter, it, spec, locationDenied, debug, motionOpacity?.value ?: 1f, tracking,
                    onNoticeTimeout, bigMapOpen, onTap, !HideHudOnHeadMotion || !hiddenForMotion,
                    countdown, quantum, wallClock, bigMapMode, headingTracking.takeIf { tracking },
                    skyBandSettings.takeIf { showGeospatialConsent }, viewport, onMapPan, onMapHold, slots.navReady,
                ) { surface, content ->
                    PlacedPanel(session, surface, dpPerMeter, it, check, content)
                }
            }
        }
    }
}

@Composable
@SubspaceComposable
internal fun SpatialHudPanels(
    dpPerMeter: Float,
    scale: Float,
    spec: HudVisualSpec,
    locationDenied: Boolean,
    debug: HudDebugOptions,
    motionOpacity: Float,
    tracking: Boolean,
    onNoticeTimeout: () -> Unit,
    bigMapOpen: Boolean,
    onTap: (HudTarget) -> Unit,
    inputEnabled: Boolean,
    countdown: CountdownPresentation?,
    quantum: HudQuantum,
    wallClock: WallClock,
    bigMapMode: BigMapMode,
    headingTracking: HeadingTracking?,
    geospatialSettings: SkyBandSettings?,
    viewport: BigMapViewport,
    onMapPan: () -> Unit,
    onMapHold: (NavPoint) -> Unit,
    navPanelReady: Boolean,
    placePanel: @Composable @SubspaceComposable (
        surface: HudSurface, content: @Composable () -> Unit,
    ) -> Unit,
) {
    val physicalDpPerMeter = dpPerMeter / scale
    val mapSpec = rememberMapSpec(spec, locationDenied)
    val mapVisible = spec is HudVisualSpec.Clusters && !locationDenied
    val showBigMap = bigMapOpen && mapVisible
    val map = rememberSharedMapPresentation(showBigMap, mapVisible, QuantumEffectsEnabled)
    val buttonsAlpha = map.buttonsAlpha(QuantumEffectsEnabled)
    val buttonsOpen = showBigMap && map.large && !map.hidden
    if (mapSpec != null) {
        val distance = 1.65f
        placePanel(HudSurface(
            HudPlacement.pose(0f, 0f, distance),
            2 * distance / Layout.distanceMeters * HudPlacement.planeHalfSpan(32f),
            2 * distance / Layout.distanceMeters * HudPlacement.planeHalfSpan(22f),
            enabled = showBigMap && inputEnabled, flatCorners = true,
        )) {
            Box(Modifier.fillMaxSize().hudClick(HudTarget.OUTSIDE, onTap, showBigMap && inputEnabled))
        }
    }
    placePanel(HudSurface(HudPlacement.statsPose(), HudPlacement.statsWidthMeters, HudPlacement.statsHeightMeters,
        alpha = motionOpacity, measure = HudPanel.LEFT)) {
        val textScale = Layout.textScale(physicalDpPerMeter)
        if (geospatialSettings != null && geospatialSettings.geospatialConsent == null &&
            spec is HudVisualSpec.Clusters && !spec.showSafetyNotice && !showBigMap && countdown == null
        ) {
            GeospatialConsentPanel(geospatialSettings, textScale, inputEnabled)
        } else {
            StatsPanel(
                spec, locationDenied, textScale,
                Modifier.fillMaxSize().clipToBounds().hudClick(HudTarget.LEFT, onTap, inputEnabled)
                    .capturePanelOnNextDraw(debug.leftCaptureName("xr", spec)),
                onNoticeTimeout,
                quantum.left,
                wallClock,
            )
        }
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
    val diameter by animateFloatAsState(mapSpec?.mapDiameter ?: Layout.walkingDiameter, tween(HudAnimationMillis))
    val size = HudPlacement.mapSizeMeters(diameter)
    if (navPanelReady) {
        val visual = mapSpec?.rightClusterNavigation(!mapVisible || !map.settledMini || showBigMap)
        SpatialNavGuidance(visual, size, dpPerMeter, scale, mapSpec?.opacity ?: 1f, motionOpacity,
            headingTracking, placePanel)
    }
    if (mapSpec != null) {
        val raster = HudPlacement.bigMapSizeMeters
        val miniScale = size / raster
        val measure = when {
            !mapVisible || map.hidden -> null
            map.large -> HudPanel.BIGMAP
            else -> HudPanel.RIGHT
        }
        val panel = if (map.large) HudPanel.BIGMAP else HudPanel.RIGHT
        placePanel(HudSurface(HudPlacement.pose(panel.yaw, panel.pitch), raster, raster,
            alpha = if (map.hidden) 0f else motionOpacity, visualScale = if (map.large) 1f else miniScale,
            enabled = mapVisible, circular = true, measure = measure)) {
            SharedMap(mapSpec, debug, map, mapVisible, showBigMap, miniScale, onTap,
                Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "right").takeIf { mapVisible && map.settledMini }),
                inputEnabled, (HudPlacement.bigMapArrowHeightMeters * physicalDpPerMeter).dp,
                bigMapMode, viewport, onMapPan, onMapHold, quantum)
        }
        val label = rememberBigMapDestinationLabel(mapSpec, showBigMap)
        val buttons = rememberBigMapButtons(mapSpec.destinationPrompt, showBigMap)
        // The preview reuses the first two panel slots. All five entities keep their original size.
        for ((i, slot) in HudPlacement.bigMapButtons.withIndex()) {
            val button = buttons.getOrNull(i)
            val title = button?.buttonLabel(label, bigMapMode)
            val motion = rememberBigMapButtonMotion(map.animation, buttonsOpen, QuantumEffectsEnabled)
            placePanel(HudSurface(remember(slot) { slot.pose() }, slot.width, slot.height,
                alpha = motionOpacity * buttonsAlpha, flatCorners = true,
                enabled = motionOpacity * buttonsAlpha > 0f && (title != null || motion.rippling))) {
                if (button != null) {
                    BigMapButton(title, button.target, mapSpec.opacity, map.animation, buttonsOpen,
                        buttonsOpen && map.acceptsLargeInput && inputEnabled, QuantumEffectsEnabled,
                        (HudPlacement.bigMapButtonDiameterMeters * physicalDpPerMeter).dp,
                        onTap, Modifier.fillMaxSize(), motion)
                }
            }
        }
    }
    if (debug.capture && spec is HudVisualSpec.Fast) {
        placePanel(HudSurface(HudPlacement.pose(Layout.rightYaw, Layout.pitch), 0.1f, 0.1f)) {
            Box(Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "right")))
        }
    }

    if (spec !is HudVisualSpec.Fast && debug.grid && !showBigMap && (!mapVisible || map.settledMini) && tracking) {
        placePanel(HudSurface(HudPlacement.pose(0f, 0f), gridWidthMeters, gridHeightMeters, measure = HudPanel.GRID)) {
            DebugAngleGrid(Modifier.fillMaxSize().capturePanelOnNextDraw(debug.captureName("xr", "grid")))
        }
    }
}

internal data class HudSurface(
    val pose: Pose,
    val width: Float,
    val height: Float,
    val alpha: Float = 1f,
    val visualScale: Float = 1f,
    val enabled: Boolean = true,
    val circular: Boolean = false,
    val flatCorners: Boolean = false,
    val measure: HudPanel? = null,
)

@Composable
@SubspaceComposable
private fun PlacedPanel(
    session: Session,
    surface: HudSurface,
    dpPerMeter: Float,
    scale: Float,
    check: HudPlacementCheck?,
    content: @Composable () -> Unit,
) {
    var entity by remember { mutableStateOf<Entity?>(null) }
    val placement = HudPlacement.modifier(surface.pose, dpPerMeter, scale)
        .scale(surface.visualScale).alpha(surface.alpha)
    SceneCoreEntity(factory = {
        Entity.create(session, "Aura HUD panel").apply { if (!surface.enabled) setEnabled(false) }
    }, modifier = placement, update = {
        // rc01 alpha only changes rendering. Disable the parent to remove all child hit targets.
        if (it.isEnabled(includeParents = false) != surface.enabled) it.setEnabled(surface.enabled)
        if (check != null) entity = it
    }) {
        val size = SubspaceModifier.width((surface.width * dpPerMeter / scale).dp)
            .height((surface.height * dpPerMeter / scale).dp)
        val measuredSize = if (check == null) size else {
            var pixels by remember { mutableStateOf<IntSize?>(null) }
            val reference = entity
            val name = surface.measure
            val dimensions = pixels
            DisposableEffect(name, reference, dimensions, check) {
                if (name != null && reference != null && dimensions != null) {
                    val density = session.scene.virtualPixelDensity
                    check.panels[name] = HudPlacementCheck.Panel(reference,
                        density.convertPixelsToMeters(dimensions.width.toFloat()),
                        density.convertPixelsToMeters(dimensions.height.toFloat()))
                }
                onDispose { if (name != null) check.panels.remove(name) }
            }
            size.onGloballyPositioned { pixels = IntSize(it.size.width, it.size.height) }
        }
        val shape = when {
            surface.circular -> SpatialRoundedCornerShape(CornerSize(50))
            surface.flatCorners -> SpatialRoundedCornerShape(CornerSize(0.dp))
            else -> SpatialPanelDefaults.shape
        }
        SpatialPanel(measuredSize, shape = shape, content = content)
    }
}

private fun formatHeadSpeed(speed: Double?, peak: Double): String {
    val current = speed?.let { "%.2f".format(Locale.ROOT, it) } ?: "unavailable"
    return "speed=$current peak=${"%.2f".format(Locale.ROOT, peak)}"
}
