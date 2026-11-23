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
import androidx.compose.ui.graphics.Color
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
import io.github.yamichi77.aurawalkinfo.heading.HeadingEstimator
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal const val SCENE_UNDERSTANDING_COARSE = "androidx.xr.runtime.manifest.SCENE_UNDERSTANDING_COARSE"
private const val TAG = "AuraSpatial"
private val MarkColors = listOf(Color(0xFF7FDBFF))

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
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val scene = SpatialMarkScene(session, context, debug)
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
                var nextPositionAt = 0L
                snapshotFlow {
                    // Reevaluate signal eligibility immediately even while the navigation marks stay active.
                    spatialUpdateMode(hasTargets, settings.spatialMarksEnabled, walk.motionState) to
                        decisions?.get(MarkKind.SIGNAL.ordinal)?.target?.signal?.intersectionId
                }
                    .collectLatest { (mode, _) ->
                        if (mode == SpatialUpdateMode.FAST) {
                            scene.draw(MarkReason.FAST, decisions, null, SystemClock.elapsedRealtime())
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
                                val north = settings.northOverride ?: heading.estimate(now)
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
                                    val referenceEye = fixEye
                                    reason = when {
                                        !anchorGranted -> MarkReason.ANCHOR_UNAVAILABLE
                                        referenceEye == null -> MarkReason.TRACKING_LOST
                                        now < nextPositionAt -> null
                                        else -> {
                                            nextPositionAt = now + MARK_POSITION_INTERVAL_MS
                                            scene.position(
                                                checkNotNull(eye),
                                                SpatialFix(checkNotNull(point), referenceEye.markVector(), checkNotNull(north).offsetDegrees),
                                                targets, now,
                                            )
                                        }
                                    }
                                }
                            }
                            scene.draw(reason, targets, lastEye, now)
                            interval = spatialUpdateIntervalMillis(mode, scene.isFading)
                            if (interval != null) delay(interval)
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (debug) Log.w(TAG, "HIDE all reason=RENDER_UNAVAILABLE type=${error.javaClass.simpleName}")
            } finally {
                scene.close()
            }
        }
    }
}

private class SpatialMarkScene(
    private val session: Session,
    private val context: Context,
    private val debug: Boolean,
) : AutoCloseable {
    private val marks = MarkKind.entries.map(::SceneMark)
    private var anchor: Anchor? = null
    private var anchorSpace: AnchorSpace? = null
    private var anchorFailure = false
    private var prepared = false
    val isFading get() = marks.any { it.isFading }
    val hasVisibleOrFadingMarks get() = marks.any { it.opacity > 0f || it.isFading }

    private fun anchorPose(): Pose? =
        anchor?.state?.value?.takeIf { it.trackingState == TrackingState.TRACKING && anchorSpace?.state == AnchorSpace.State.ANCHORED }
            ?.pose

    suspend fun position(eye: Vector3, fix: SpatialFix, decisions: List<MarkDecision>, now: Long): MarkReason? {
        if (anchorFailure) return MarkReason.ANCHOR_UNAVAILABLE
        if (anchor?.state?.value?.trackingState == TrackingState.STOPPED) {
            close()
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
            // All suspending XR resource creation finishes before any mark can be visible.
            val parent = checkNotNull(anchorSpace)
            try {
                marks.forEach { it.prepare(session, context, parent) }
            } catch (cancelled: CancellationException) {
                // A change in update mode can cancel construction before the mesh is ready.
                close()
                throw cancelled
            }
            prepared = true
            // Read the safety inputs again after the suspension before placing anything.
            return MarkReason.ANCHOR_UNAVAILABLE
        }
        marks.zip(decisions).forEach { (mark, decision) ->
            val target = decision.target ?: return@forEach
            val world = GpsSpatialProjector.project(fix, target.point, mark.kind.baseHeight)
            val center = fromPerception.transformPoint(world.vector3()).markVector()
            val forward = fromPerception.transformVector(markForward(target, fix.northOffsetDegrees).vector3()).markVector()
            val up = fromPerception.transformVector(Vector3(0f, 1f, 0f)).markVector()
            mark.position(target, center, forward, up, now)
        }
        return null
    }

    fun draw(reason: MarkReason?, decisions: List<MarkDecision>?, eye: Vector3?, now: Long) {
        val view = if (eye == null) null else anchorPose()?.inverse?.let { fromPerception ->
            MarkView(
                fromPerception.transformPoint(eye).markVector(),
                fromPerception.transformVector(Vector3(0f, 1f, 0f)).markVector(),
            )
        }
        for ((index, mark) in marks.withIndex()) {
            val decision = decisions?.get(index)
            val why = reason ?: decision?.reason ?: MarkReason.NO_FIX
            mark.draw(why, decision?.target, view, now, debug)
        }
    }

    override fun close() {
        marks.forEach { it.close() }
        anchorSpace = null
        anchor?.detach()
        anchor = null
        prepared = false
    }
}

private class SceneMark(val kind: MarkKind) : AutoCloseable {
    private var mesh: SpatialMarkMesh? = null
    private var signalPanel: SignalTotemPanel? = null
    private var lastShownSignal: SignalTotem? = null
    private var target: MarkTarget? = null
    private var position: SmoothedMarkPosition? = null
    private var forward = MarkVector(0.0, 0.0, -1.0)
    private var up = MarkVector.UP
    private var fade = MarkFade()
    private var lastReason: MarkReason? = null
    private var lastView: MarkView? = null
    private var clipKey: ClipKey? = null
    private var lastClip: ClippedMark? = null
    var opacity = 0f
        private set
    val isFading get() = fade.isAnimating

    suspend fun prepare(session: Session, context: Context, parent: AnchorSpace) {
        if (kind == MarkKind.SIGNAL) {
            signalPanel = SignalTotemPanel(session, context, parent)
            return
        }
        val created = SpatialMarkMesh(kind)
        mesh = created
        created.initialize(session, parent)
    }

    fun position(target: MarkTarget, center: MarkVector, forward: MarkVector, up: MarkVector, now: Long) {
        if (!sameMark(this.target, target)) {
            position = SmoothedMarkPosition(center)
            fade = MarkFade()
        } else {
            position?.retarget(center, now)
        }
        this.target = target
        this.forward = forward
        this.up = up
    }

    fun draw(reason: MarkReason, requested: MarkTarget?, view: MarkView?, now: Long, debug: Boolean) {
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
        val clipped = if (reason == MarkReason.FAST || center == null || stored == null || usableView == null) null
            else if (reason == MarkReason.SHOW || opacity > 0f || reason != lastReason) {
                clipMark(ClipKey(center, forward, up, usableView))
            } else null

        val actual = when {
            reason != MarkReason.SHOW -> reason
            !compatible || view == null -> MarkReason.ANCHOR_UNAVAILABLE
            clipped == null || clipped.geometry.all { it.isEmpty() } -> MarkReason.BELOW_ELEVATION
            else -> MarkReason.SHOW
        }
        opacity = fade.update(actual == MarkReason.SHOW, reason == MarkReason.FAST, now)
        mesh?.draw(clipped?.geometry.orEmpty(), MarkColors, opacity)
        if (debug && actual != lastReason) {
            val data = clipped ?: lastClip
            val range = data?.elevationRange()
            Log.i(
                TAG, "${if (actual == MarkReason.SHOW) "SHOW" else "HIDE"} mark=$kind reason=$actual " +
                    "distanceM=${data?.distance?.diagnostic() ?: "-"} " +
                    "elevationDeg=${range?.first?.diagnostic() ?: "-"}..${range?.second?.diagnostic() ?: "-"}",
            )
        }
        lastReason = actual
        if (reason == MarkReason.FAST) {
            target = null
            position = null
            lastClip = null
            clipKey = null
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

    private fun clipMark(key: ClipKey): ClippedMark {
        if (key == clipKey) return checkNotNull(lastClip)
        val (center, forward, up, view) = key
        val right = forward.cross(up)
        val strokes = markStrokes(kind, center, forward, up)
        val geometry = listOf(strokes.flatMap { it.clippedTriangles(view, up, right) })
        val result = ClippedMark(geometry, strokes, center, view)
        clipKey = key
        lastClip = result
        return result
    }

    override fun close() {
        mesh?.close()
        mesh = null
        signalPanel?.close()
        signalPanel = null
        lastShownSignal = null
        target = null
        position = null
        fade = MarkFade()
        lastView = null
        clipKey = null
        lastClip = null
        opacity = 0f
    }
}

private data class ClipKey(
    val center: MarkVector, val forward: MarkVector, val up: MarkVector, val view: MarkView,
)

private class ClippedMark(
    val geometry: List<List<MarkVector>>, private val strokes: List<MarkStroke>,
    private val center: MarkVector, private val view: MarkView,
) {
    val distance get() = (center - view.eye).length()

    fun elevationRange(): Pair<Double, Double> {
        val points = geometry.flatten().ifEmpty { strokes.flatMap { listOf(it.start, it.end) } }
        return points.minOf(view::elevation) to points.maxOf(view::elevation)
    }
}

private fun sameMark(first: MarkTarget?, second: MarkTarget?): Boolean {
    if (first == null || second == null) return false
    return if (first.signal != null && second.signal != null) first.signal.intersectionId == second.signal.intersectionId
    else first.point == second.point
}

private fun MarkVector.vector3() = Vector3(x.toFloat(), y.toFloat(), z.toFloat())
private fun Vector3.markVector() = MarkVector(x.toDouble(), y.toDouble(), z.toDouble())
private fun Double.diagnostic() = String.format(Locale.ROOT, "%.2f", this)
