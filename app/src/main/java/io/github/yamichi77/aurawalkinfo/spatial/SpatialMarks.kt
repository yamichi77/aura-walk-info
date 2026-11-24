package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.geospatial.VpsFix
import io.github.yamichi77.aurawalkinfo.heading.HeadingEstimator
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.distanceTo
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal const val SCENE_UNDERSTANDING_COARSE = "android.permission.SCENE_UNDERSTANDING_COARSE"
private const val TAG = "AuraSpatial"

@Composable
internal fun SpatialMarks(
    state: WalkSessionState,
    nav: NavState,
    signal: PicsCandidate?,
    settings: SkyBandSettings,
    heading: HeadingTracking,
    anchorGranted: Boolean,
    locationDenied: Boolean,
    debug: Boolean,
) {
    val session = LocalSession.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val current = remember(state.mapCenter, locationDenied) {
        state.mapCenter?.takeIf {
            !locationDenied && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }?.let { NavPoint(it.latitude, it.longitude) }
    }
    val decisions by rememberUpdatedState(remember(current, nav, signal, state.motionState) {
        current?.let { point -> MarkKind.entries.map { decideMark(it, point, nav, signal, state.motionState) } }
    })
    val fix by rememberUpdatedState(current)
    val walk by rememberUpdatedState(state)
    val hasTargets by rememberUpdatedState(nav is NavState.Guiding || nav is NavState.Rerouting || signal != null)
    LaunchedEffect(session, context, owner, settings, heading, anchorGranted, debug) {
        if (session == null) return@LaunchedEffect
        // Keep renderers across pauses. ON_PAUSE also precedes Session's ON_DESTROY cleanup.
        val scene = SpatialMarkScene(session, context, debug)
        try {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (debug) launch {
                    snapshotFlow { settings.northOverride }.collectLatest { override ->
                        if (override == null) return@collectLatest
                        val yaw = heading.awaitTrackedYaw()
                        Log.i(TAG, "NORTH_OVERRIDE rawHead=${override.bearingForYaw(yaw).diagnostic()} " +
                            "uncertainty=${override.uncertaintyDegrees.diagnostic()}")
                    }
                }
                try {
                    var device: ArDevice? = null
                    var fixTime: Long? = null
                    var fixPoint: NavPoint? = null
                    var fixEye: Vector3? = null
                    var lastEye: Vector3? = null
                    var nextRelativeLogAt = 0L
                    var nextPositionAt = 0L
                    var wasVps = false
                    var placementFailure: MarkReason? = null
                    snapshotFlow {
                        // Reevaluate signal eligibility immediately even while the navigation marks stay active.
                        spatialUpdateMode(hasTargets, settings.spatialMarksEnabled, walk.motionState) to
                            decisions?.get(MarkKind.SIGNAL.ordinal)?.target?.signal?.intersectionId
                    }
                        .collectLatest { (mode, _) ->
                            if (mode == SpatialUpdateMode.FAST) {
                                scene.draw(MarkReason.FAST, decisions, null, null, SystemClock.elapsedRealtime())
                                return@collectLatest
                            }
                            val fadingOut = mode != SpatialUpdateMode.ACTIVE && scene.hasVisibleOrFadingMarks
                            var interval = spatialUpdateIntervalMillis(mode, fadingOut || scene.isFading)
                            while (interval != null) {
                                val now = SystemClock.elapsedRealtime()
                                if (device == null && session.config.deviceTracking == DeviceTrackingMode.SPATIAL) {
                                    device = ArDevice.getInstance(session)
                                }
                                val eye = device?.state?.value?.takeIf { it.trackingState == TrackingState.TRACKING }
                                    ?.devicePose?.translation?.takeIf { it.markVector().isFinite() }
                                if (eye != null) lastEye = eye
                                val targets = decisions
                                var reason = mode.hideReason
                                if (mode == SpatialUpdateMode.ACTIVE) {
                                    val sample = walk
                                    val point = fix
                                    val gpsNorth = settings.northOverride ?: heading.estimate(now)
                                    val vps = if (settings.northOverride == null) heading.geospatial.fix(now) else null
                                    val north = vps?.north ?: gpsNorth
                                    reason = spatialBlocker(
                                        sample.motionState, settings.spatialMarksEnabled,
                                        eye != null && !settings.simulateTrackingLoss, north, point, sample.lastFixAccuracyMeters,
                                    )
                                    if (fixTime != sample.lastFixTimeMillis || fixPoint != point) {
                                        fixTime = sample.lastFixTimeMillis
                                        fixPoint = point
                                        fixEye = null
                                    }
                                    if (fixEye == null && eye != null && point != null) {
                                        fixEye = heading.poseForFix(fixTime)?.perceptionPosition ?: eye.takeIf {
                                            (debug && fixTime == null) ||
                                                fixTime?.let { now - it in 0..HeadingEstimator.MAX_POSE_GAP_MS } == true
                                        }
                                    }

                                    if (reason == null && targets?.any { it.target != null } == true) {
                                        val gpsFix = fixEye?.let { referenceEye ->
                                            gpsNorth?.let { SpatialFix(checkNotNull(point), referenceEye.markVector(), it.offsetDegrees) }
                                        }
                                        val fallbackReason = spatialBlocker(
                                            sample.motionState, settings.spatialMarksEnabled,
                                            eye != null && !settings.simulateTrackingLoss, gpsNorth, point, sample.lastFixAccuracyMeters,
                                        ) ?: MarkReason.TRACKING_LOST.takeIf { gpsFix == null }
                                        reason = when {
                                            !anchorGranted -> MarkReason.ANCHOR_UNAVAILABLE
                                            vps == null && fallbackReason != null -> fallbackReason
                                            now < nextPositionAt && wasVps == (vps != null) -> placementFailure
                                            else -> {
                                                nextPositionAt = now + MARK_POSITION_INTERVAL_MS
                                                wasVps = vps != null
                                                scene.position(
                                                    checkNotNull(eye), gpsFix, vps, fallbackReason, targets, now,
                                                ).also { placementFailure = it }
                                            }
                                        }
                                    }
                                }
                                scene.draw(reason, targets, lastEye, fix, now)
                                if (debug && now >= nextRelativeLogAt) {
                                    // Where each mark is as seen from the head (T20: marks were logged as shown but not seen).
                                    nextRelativeLogAt = now + 1_000L
                                    device?.state?.value?.takeIf { it.trackingState == TrackingState.TRACKING }
                                        ?.devicePose?.let { scene.logRelative(it, now) }
                                }
                                interval = spatialUpdateIntervalMillis(mode, scene.isFading)
                                if (interval != null) delay(interval)
                            }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (debug) Log.w(TAG, "HIDE all reason=RENDER_UNAVAILABLE type=${error.javaClass.simpleName}")
                } finally {
                    if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) scene.hide()
                }
            }
        } finally {
            if (owner.lifecycle.canRemoveSpatialEntities(context)) scene.removeFromScene()
        }
    }
}

private class SpatialMarkScene(
    private val session: Session,
    private val context: Context,
    private val debug: Boolean,
) {
    private val marks = MarkKind.entries.map(::SceneMark)
    private var anchor: Anchor? = null
    private var anchorSpace: AnchorSpace? = null
    private var anchorFailure = false
    private var prepared = false
    private var lastVpsProjection: Boolean? = null
    val isFading get() = marks.any { it.isFading }
    val hasVisibleOrFadingMarks get() = marks.any { it.opacity > 0f || it.isFading }

    private fun anchorPose(): Pose? =
        anchor?.state?.value?.takeIf { it.trackingState == TrackingState.TRACKING && anchorSpace?.state == AnchorSpace.State.ANCHORED }
            ?.pose

    suspend fun position(
        eye: Vector3, gpsFix: SpatialFix?, vps: VpsFix?, fallbackReason: MarkReason?,
        decisions: List<MarkDecision>, now: Long,
    ): MarkReason? {
        if (anchorFailure) return MarkReason.ANCHOR_UNAVAILABLE
        if (anchor?.state?.value?.trackingState == TrackingState.STOPPED) {
            removeFromScene()
        }
        if (anchor == null) {
            // Keep a single nearby anchor. The three marks move only relative to this world-space parent.
            val result = Anchor.create(session, Pose(eye)) as? AnchorCreateSuccess ?: return MarkReason.ANCHOR_UNAVAILABLE
            anchor = result.anchor
            anchorSpace = AnchorSpace.create(session, result.anchor)
            if (anchorSpace?.state == AnchorSpace.State.ERROR) {
                anchorFailure = true
                return MarkReason.ANCHOR_UNAVAILABLE
            }
        }
        val fromPerception = anchorPose()?.inverse ?: return MarkReason.ANCHOR_UNAVAILABLE
        if (!prepared) {
            val parent = checkNotNull(anchorSpace)
            marks.forEach { it.prepare(session, context, parent) }
            prepared = true
            // Recheck safety inputs on the next tick after creating the anchor.
            return MarkReason.ANCHOR_UNAVAILABLE
        }
        val vpsPositions = vps?.let { fix ->
            marks.zip(decisions).map { (mark, decision) ->
                val target = decision.target ?: return@map null
                fix.projector.project(null, target.point, mark.kind.baseHeight) ?: return@let null
            }
        }
        val usingVps = vpsPositions != null
        if (vps != null || lastVpsProjection == true) {
            val projection = when {
                usingVps -> "source=VPS reason=READY"
                vps != null -> "source=GPS reason=CONVERSION_FAILED"
                else -> "source=GPS reason=VPS_UNAVAILABLE"
            }
            if (lastVpsProjection != usingVps) Log.i("AuraGeo", "marks $projection")
        }
        lastVpsProjection = usingVps.takeIf { vps != null }
        if (!usingVps && fallbackReason != null) return fallbackReason
        val northOffset = if (usingVps) checkNotNull(vps).north.offsetDegrees else checkNotNull(gpsFix).northOffsetDegrees
        val view = fromPerception.markView(eye)
        marks.zip(decisions).forEachIndexed { index, (mark, decision) ->
            val target = decision.target ?: return@forEachIndexed
            val world = vpsPositions?.get(index) ?: GpsSpatialProjector.project(gpsFix, target.point, mark.kind.baseHeight)
            val center = fromPerception.transformPoint(world.vector3()).markVector()
            val forward = fromPerception.transformVector(markForward(target, northOffset).vector3()).markVector()
            mark.position(target, center, forward, usingVps, now, view)
        }
        return null
    }

    fun logRelative(head: Pose, now: Long) {
        val anchor = anchorPose() ?: return
        for (mark in marks) {
            val center = mark.centerAt(now) ?: continue
            val relative = head.inverse.transformPoint(anchor.transformPoint(center.vector3()))
            val yaw = Math.toDegrees(kotlin.math.atan2(relative.x.toDouble(), -relative.z.toDouble()))
            val elevation = Math.toDegrees(
                kotlin.math.atan2(relative.y.toDouble(), kotlin.math.hypot(relative.x.toDouble(), relative.z.toDouble())),
            )
            Log.i(
                TAG, "RELATIVE mark=${mark.kind} opacity=${mark.opacity.toDouble().diagnostic()} " +
                    "yawDeg=${yaw.diagnostic()} elevationDeg=${elevation.diagnostic()} " +
                    "distanceM=${relative.length.toDouble().diagnostic()}",
            )
        }
    }

    fun draw(reason: MarkReason?, decisions: List<MarkDecision>?, eye: Vector3?, current: NavPoint?, now: Long) {
        val view = if (eye == null) null else anchorPose()?.inverse?.markView(eye)
        for ((index, mark) in marks.withIndex()) {
            val decision = decisions?.get(index)
            val why = reason ?: decision?.reason ?: MarkReason.NO_FIX
            mark.draw(why, decision?.target, view, current, now, debug)
        }
    }

    fun hide() {
        marks.forEach { it.hide() }
    }

    fun removeFromScene() {
        marks.forEach { it.removeFromScene() }
        anchorSpace = null
        anchor?.detach()
        anchor = null
        prepared = false
    }
}

private class SceneMark(val kind: MarkKind) {
    private var standingPanel: StandingMarkPanel? = null
    private var signalPanel: SignalTotemPanel? = null
    private var lastShownSignal: SignalTotem? = null
    private var target: MarkTarget? = null
    private var position: SmoothedMarkPosition? = null
    private var destinationGround: MarkVector? = null
    private var forward = MarkVector(0.0, 0.0, -1.0)
    private val maxOpacity = if (kind == MarkKind.SIGNAL) MARK_MAX_OPACITY else 1f
    private var fade = MarkFade(maxOpacity)
    private var lastReason: MarkReason? = null
    private var lastView: MarkView? = null
    private var lastFacing: MarkVector? = null
    private var nearTurn = false
    var opacity = 0f
        private set
    val isFading get() = fade.isAnimating

    fun centerAt(now: Long): MarkVector? = position?.at(now)

    fun prepare(session: Session, context: Context, parent: AnchorSpace) {
        if (kind == MarkKind.SIGNAL) {
            signalPanel = SignalTotemPanel(session, context, parent)
        } else {
            standingPanel = StandingMarkPanel(session, context, parent, kind)
        }
    }

    private var usingVps = false

    fun position(target: MarkTarget, center: MarkVector, forward: MarkVector, usingVps: Boolean, now: Long, view: MarkView) {
        val next = if (kind == MarkKind.DESTINATION) {
            destinationGround = center
            destinationHeadTarget(center, view)
        } else center
        if (!sameMark(this.target, target)) {
            // The destination height changes by much less than a metre between normal walking updates.
            position = if (kind == MarkKind.DESTINATION) SmoothedMarkPosition(next, minimumChangeMeters = 0.05)
                else SmoothedMarkPosition(next)
            fade = MarkFade(maxOpacity)
        } else {
            position?.retarget(next, now, sourceChanged = this.usingVps != usingVps)
        }
        this.usingVps = usingVps
        this.target = target
        this.forward = forward
    }

    fun draw(reason: MarkReason, requested: MarkTarget?, view: MarkView?, current: NavPoint?, now: Long, debug: Boolean) {
        val center = position?.at(now)
        val stored = target
        val compatible = sameMark(stored, requested)
        if (kind == MarkKind.SIGNAL) {
            drawSignal(reason, requested?.signal?.takeIf { compatible }, center, view, now, debug)
            if (reason == MarkReason.FAST) {
                target = null
                position = null
            }
            return
        }
        val usableView = view ?: lastView
        if (view != null) lastView = view
        if (kind == MarkKind.TURN && stored != null && current != null) {
            nearTurn = isNearTurn(current.distanceTo(stored.point))
        }
        val layout = if (reason != MarkReason.FAST && center != null && usableView != null &&
            (reason == MarkReason.SHOW || opacity > 0f)
        ) {
            val facing = markPanelFacing(center, usableView, lastFacing ?: forward * -1.0)
            lastFacing = facing
            if (kind == MarkKind.TURN) standingArrow(center, usableView, facing, forward, nearTurn)
            else destinationGround?.let { standingDestination(center, it, usableView, facing) }
        } else null

        val actual = when {
            reason != MarkReason.SHOW -> reason
            !compatible || view == null || center == null || standingPanel == null -> MarkReason.ANCHOR_UNAVAILABLE
            layout == null -> MarkReason.BELOW_ELEVATION
            else -> MarkReason.SHOW
        }
        opacity = fade.update(actual == MarkReason.SHOW, reason == MarkReason.FAST, now) * arrowOpacity(nearTurn)
        if (layout != null && usableView != null) standingPanel?.draw(layout, usableView.up, opacity)
        else standingPanel?.hide()
        if (debug && actual != lastReason) {
            val distance = if (center != null && usableView != null) (center - usableView.eye).length() else null
            val elevation = if (layout != null && usableView != null) {
                val data = layout.artwork
                val clippedHeight = if (data is MarkArtwork.Destination) data.height - data.visibleHeight else 0.0
                val right = usableView.up.cross(layout.facing)
                val lowerEdge = layout.bottom + usableView.up * clippedHeight + right * (data.width / (2 * right.length()))
                val top = layout.bottom + usableView.up * data.height
                "${usableView.elevation(lowerEdge).diagnostic()}..${usableView.elevation(top).diagnostic()}"
            } else "-..-"
            Log.i(
                TAG, "${if (actual == MarkReason.SHOW) "SHOW" else "HIDE"} mark=$kind reason=$actual " +
                    "distanceM=${distance?.diagnostic() ?: "-"} elevationDeg=$elevation",
            )
        }
        lastReason = actual
        if (reason == MarkReason.FAST) {
            target = null
            position = null
            destinationGround = null
            lastFacing = null
        }
    }

    private fun drawSignal(
        reason: MarkReason,
        signal: PicsCandidate?,
        center: MarkVector?,
        view: MarkView?,
        now: Long,
        debug: Boolean,
    ) {
        val actual = when {
            reason != MarkReason.SHOW -> reason
            signal == null || center == null || view == null || signalPanel == null -> MarkReason.ANCHOR_UNAVAILABLE
            else -> MarkReason.SHOW
        }
        opacity = fade.signalOpacity(actual, now)
        if (opacity > 0f && signal != null && center != null && view != null) {
            val totem = signal.toSignalTotem()
            val placement = placeSignalTotem(center, view.eye, view.up, totem.heightMeters)
            signalPanel?.draw(totem, placement, view.up, forward, opacity)
            if (debug && lastShownSignal != totem) {
                val bottom = placement.center - view.up * (totem.heightMeters / 2)
                val aspects = totem.rows.joinToString(",") { it.aspect.name }
                val segments = totem.rows.joinToString(",") { it.segments.toString() }
                Log.i(TAG, "SHOW mark=SIGNAL reason=SHOW phase=${totem.freshness} rows=${totem.rows.size} " +
                    "aspects=$aspects segments=$segments bottomElevationDeg=${view.elevation(bottom).diagnostic()}")
            }
            lastShownSignal = totem
        } else {
            signalPanel?.hide()
            if (debug && actual != MarkReason.SHOW && (actual != lastReason || lastShownSignal != null)) {
                Log.i(TAG, "HIDE mark=SIGNAL reason=$actual")
            }
            lastShownSignal = null
        }
        lastReason = actual
    }

    fun hide() {
        standingPanel?.hide()
        signalPanel?.hide()
        resetVisibility()
    }

    private fun resetVisibility() {
        fade = MarkFade(maxOpacity)
        lastView = null
        lastFacing = null
        lastReason = null
        lastShownSignal = null
        opacity = 0f
    }

    fun removeFromScene() {
        standingPanel?.removeFromScene()
        standingPanel = null
        signalPanel?.removeFromScene()
        signalPanel = null
        target = null
        position = null
        destinationGround = null
        nearTurn = false
        resetVisibility()
    }
}

private fun sameMark(first: MarkTarget?, second: MarkTarget?): Boolean {
    if (first == null || second == null) return false
    return if (first.signal != null && second.signal != null) first.signal.intersectionId == second.signal.intersectionId
    else if (first.turnStep != null || second.turnStep != null) first.turnStep == second.turnStep
    else first.point == second.point
}

private fun MarkVector.vector3() = Vector3(x.toFloat(), y.toFloat(), z.toFloat())
private fun Vector3.markVector() = MarkVector(x.toDouble(), y.toDouble(), z.toDouble())
private fun Pose.markView(eye: Vector3) =
    MarkView(transformPoint(eye).markVector(), transformVector(Vector3(0f, 1f, 0f)).markVector())
private fun Double.diagnostic() = String.format(Locale.ROOT, "%.2f", this)
