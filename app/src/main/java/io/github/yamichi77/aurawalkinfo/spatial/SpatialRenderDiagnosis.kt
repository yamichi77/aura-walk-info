package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.PerceptionSpace
import androidx.xr.scenecore.scene
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AuraSpatialTest"
private const val BASE_DISTANCE_METERS = 4f

/** Debug only: distance of the test marks, set by `--ef distance`. Sizes scale to keep the same angle. */
internal var spatialTestDistanceMeters = BASE_DISTANCE_METERS

@Composable
internal fun SpatialRenderDiagnosis(headGranted: Boolean, anchorGranted: Boolean) {
    val session = LocalSession.current
    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(session, spatial, headGranted, anchorGranted, owner) {
        val problem = when {
            session == null || !spatial -> "SPATIAL_UI_UNAVAILABLE"
            !headGranted -> "HEAD_PERMISSION_MISSING"
            else -> null
        }
        if (problem != null) owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { logUnavailable(problem) }
        else runDiagnosis(checkNotNull(session), anchorGranted, owner.lifecycle)
    }
}

private suspend fun runDiagnosis(session: Session, anchorGranted: Boolean, lifecycle: Lifecycle) {
    val samples = Sample.entries.map(::DiagnosisEntity)
    var anchor: Anchor? = null
    var anchorSpace: AnchorSpace? = null
    var anchorPose: Pose? = null
    var anchorStatus = "-"
    var placed = false

    suspend fun place(head: Pose) = coroutineScope {
        val scene = session.scene
        for (sample in samples.filterNot { it.kind.anchored }) {
            // Convert once at placement. The saved pose is relative to the scene root.
            val localPose = xrResult {
                scene.perceptionSpace.transformPoseTo(sample.kind.poseFrom(head), scene.activitySpace)
            }
            launch { sample.create(session, scene.activitySpace, localPose) }
        }

        // Use the same nearby-anchor strategy as the normal spatial marks.
        val reference = anchorPose ?: Pose(head.translation).also { anchorPose = it }
        if (anchorSpace == null) anchorStatus = if (anchorGranted) {
            xrResult {
                val result = Anchor.create(session, reference)
                if (result is AnchorCreateSuccess) {
                    anchor = result.anchor
                    anchorSpace = AnchorSpace.create(session, result.anchor)
                    "CREATED"
                } else result.javaClass.simpleName
            }.getOrElse { it.javaClass.simpleName }
        } else "ANCHOR_PERMISSION_MISSING"
        for (sample in samples.filter { it.kind.anchored }) {
            val parent = anchorSpace
            if (parent == null || parent.state == AnchorSpace.State.ERROR) {
                sample.creation = "FAILED:${if (parent == null) anchorStatus else parent.state}"
            } else {
                val localPose = Result.success(reference.inverse.compose(sample.kind.poseFrom(head)))
                launch { sample.create(session, parent, localPose) }
            }
        }
    }

    try {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val tracking = xrResult {
                    if (session.config.deviceTracking != DeviceTrackingMode.SPATIAL) {
                        val config = Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()
                        check(session.configure(config) is SessionConfigureSuccess)
                    }
                    ArDevice.getInstance(session)
                }
                val device = tracking.getOrElse { logUnavailable("TRACKING_SETUP_${it.javaClass.simpleName}") }
                val scene = session.scene
                if (placed) samples.forEach { it.show() }
                coroutineScope {
                    var placementStarted = placed
                    while (true) {
                        val state = device.state.value
                        val head = state.devicePose.takeIf { state.trackingState == TrackingState.TRACKING }
                        if (!placementStarted && head != null) {
                            placementStarted = true
                            launch {
                                place(head)
                                placed = true
                            }
                        }
                        for (sample in samples) {
                            val parentState = if (sample.kind.anchored) {
                                anchorSpace?.let { "${it.state}/${anchor?.state?.value?.trackingState}" } ?: anchorStatus
                            } else "ActivitySpace"
                            val parentReady = !sample.kind.anchored ||
                                anchorSpace?.state == AnchorSpace.State.ANCHORED &&
                                anchor?.state?.value?.trackingState == TrackingState.TRACKING
                            sample.log(scene.perceptionSpace, head.takeIf { parentReady }, parentState, state.trackingState)
                        }
                        delay(1_000)
                    }
                }
            } finally {
                if (lifecycle.currentState != Lifecycle.State.DESTROYED) samples.forEach { it.hide() }
            }
        }
    } finally {
        if (lifecycle.canRemoveSpatialEntities(session.context)) {
            samples.forEach { it.removeFromScene() }
            anchor?.detach()
        }
    }
}

private suspend fun logUnavailable(reason: String): Nothing {
    while (true) {
        for (sample in Sample.entries) {
            Log.i(TAG, "${sample.label} creation=FAILED:$reason parent=- isEnabled=- yawDeg=- elevationDeg=- distanceM=-")
        }
        delay(1_000)
    }
}

private enum class Sample(val label: String, val anchored: Boolean, val isMesh: Boolean) {
    A_MESH("A-mesh", true, true),
    A_PANEL("A-panel", true, false),
    B_MESH("B-mesh", false, true),
    B_PANEL("B-panel", false, false);

    fun poseFrom(head: Pose): Pose {
        val scale = spatialTestDistanceMeters / BASE_DISTANCE_METERS
        // SceneCore uses +X right, +Y up and -Z forward.
        return head.compose(
            Pose(Vector3((if (anchored) -1.2f else 1.2f) * scale, (if (isMesh) 0.8f else 0f) * scale, -4f * scale)),
        )
    }
}

private class DiagnosisEntity(val kind: Sample) {
    var creation = "WAITING_FOR_HEAD"
    private var entity: Entity? = null
    private var mesh: SpatialMarkMesh? = null

    suspend fun create(session: Session, parent: Entity, localPose: Result<Pose>) {
        if (entity != null) {
            show()
            return
        }
        creation = "PENDING"
        creation = xrResult {
            val pose = localPose.getOrThrow()
            if (kind.isMesh) {
                val outline = squareOutline()
                val renderer = mesh ?: SpatialMarkMesh(listOf(outline.size)).also { mesh = it }
                entity = renderer.initialize(session, parent, pose)
                renderer.draw(listOf(outline), listOf(Color(0xFF7FDBFF)), 1f)
            } else {
                entity = PanelEntity.create(
                    session, DiagnosisLetter(session.context, kind.label.take(1)), FloatSize2d(0.6f, 0.6f),
                    kind.label, pose = pose, parent = parent,
                )
            }
            // Keep the apparent size constant so only the distance changes between runs.
            entity?.setScale(spatialTestDistanceMeters / BASE_DISTANCE_METERS)
            "CREATED distance=${spatialTestDistanceMeters}"
        }.getOrElse {
            removeFromScene()
            "FAILED:${it.javaClass.simpleName}"
        }
    }

    fun log(space: PerceptionSpace, head: Pose?, parentState: String, tracking: TrackingState) {
        val node = entity
        val enabled = node?.let { xrResult { it.isEnabled() }.getOrNull() }
        val relative = if (head == null || node == null) null else xrResult {
            val world = node.transformPoseTo(Pose.Identity, space)
            head.inverse.transformPoint(world.translation)
        }.getOrNull()
        val yaw = relative?.let { Math.toDegrees(atan2(it.x.toDouble(), -it.z.toDouble())) }
        val elevation = relative?.let {
            Math.toDegrees(atan2(it.y.toDouble(), hypot(it.x.toDouble(), it.z.toDouble())))
        }
        Log.i(
            TAG, "${kind.label} creation=$creation parent=$parentState isEnabled=${enabled ?: "-"} " +
                "tracking=$tracking yawDeg=${yaw.diagnostic()} elevationDeg=${elevation.diagnostic()} " +
                "distanceM=${relative?.length?.toDouble().diagnostic()}",
        )
    }

    fun show() {
        entity?.setEnabled(true)
    }

    fun hide() {
        if (kind.isMesh) mesh?.hide()
        else entity?.hideSpatialEntity()
    }

    fun removeFromScene() {
        if (kind.isMesh) mesh?.removeFromScene()
        else entity?.hideSpatialEntity(detach = true)
        entity = null
        mesh = null
    }
}

private class DiagnosisLetter(context: Context, private val letter: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(224, 247, 255)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        paint.textSize = minOf(width, height) * 0.8f
        val baseline = (height - paint.ascent() - paint.descent()) / 2f
        canvas.drawText(letter, width / 2f, baseline, paint)
    }
}

private fun squareOutline(): List<MarkVector> {
    val outer = 0.5
    val inner = outer - 0.05
    fun rect(left: Double, bottom: Double, right: Double, top: Double): List<MarkVector> {
        val a = MarkVector(left, bottom, 0.0)
        val b = MarkVector(right, bottom, 0.0)
        val c = MarkVector(right, top, 0.0)
        val d = MarkVector(left, top, 0.0)
        return listOf(a, b, c, a, c, d, a, c, b, a, d, c)
    }
    return rect(-outer, inner, outer, outer) + rect(-outer, -outer, outer, -inner) +
        rect(-outer, -inner, -inner, inner) + rect(inner, -inner, outer, inner)
}

private inline fun <T> xrResult(action: () -> T): Result<T> = try {
    Result.success(action())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun Double?.diagnostic() = this?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
