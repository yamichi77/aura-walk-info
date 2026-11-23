package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.onGloballyPositioned
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import io.github.yamichi77.aurawalkinfo.safety.HeadMotionGate
import kotlin.math.atan2
import kotlin.math.hypot

internal data class SkyMarkLayout(
    val pitch: Float,
    val widthMeters: Float,
    val heightMeters: Float,
    val glyphMeters: Float,
)

internal fun skyMarkLayout(kind: SkyMarkKind): SkyMarkLayout {
    fun span(halfAngle: Float) = 2 * HudPlacement.planeHalfSpan(halfAngle)
    return when (kind) {
        SkyMarkKind.DISTANCE -> SkyMarkLayout(15f, span(2.5f), span(0.9f), 0f)
        SkyMarkKind.SIGNAL -> SkyMarkLayout(17.5f, span(0.8f), span(1.1f), span(0.5f))
        SkyMarkKind.DESTINATION -> SkyMarkLayout(17.5f, span(0.8f), span(0.8f), span(0.5f))
        else -> SkyMarkLayout(17.5f, span(0.8f), span(0.8f), span(0.6f))
    }
}

internal data class SkyPitchBounds(val min: Double, val max: Double) {
    val insideBand: Boolean get() = min >= 14.0 && max <= 19.0
}

/** Include the extrema along each edge, even when tracking slightly tilts the panel. */
internal fun skyPitchBounds(pose: Pose, widthMeters: Float, heightMeters: Float): SkyPitchBounds {
    fun pitch(point: Vector3) = Math.toDegrees(atan2(point.y, hypot(point.x, point.z)).toDouble())
    val x = widthMeters / 2
    val y = heightMeters / 2
    val corners = listOf(Vector3(-x, -y, 0f), Vector3(x, -y, 0f), Vector3(x, y, 0f), Vector3(-x, y, 0f))
        .map(pose::transformPoint)
    val pitches = buildList {
        add(pitch(pose.translation))
        corners.forEachIndexed { index, start ->
            add(pitch(start))
            val delta = corners[(index + 1) % corners.size] - start
            val horizontalDot = start.x * delta.x + start.z * delta.z
            val horizontalLengthSquared = start.x * start.x + start.z * start.z
            val deltaLengthSquared = delta.x * delta.x + delta.z * delta.z
            // The derivative of y / hypot(x, z) vanishes at this fraction of the edge.
            val t = (start.y * horizontalDot - delta.y * horizontalLengthSquared) /
                (delta.y * horizontalDot - start.y * deltaLengthSquared)
            if (t > 0f && t < 1f) add(pitch(start + delta * t))
        }
    }
    return SkyPitchBounds(pitches.min(), pitches.max())
}

@Composable
@SubspaceComposable
internal fun MeasuredSkyPanel(
    session: Session,
    kind: SkyMarkKind,
    placement: SubspaceModifier,
    size: SubspaceModifier,
    shape: SpatialRoundedCornerShape,
    scale: Float,
    content: @Composable () -> Unit,
) {
    val scene = session.scene
    var entity by remember { mutableStateOf<Entity?>(null) }
    var measuredSize by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    LaunchedEffect(session, entity, measuredSize) {
        val panel = entity ?: return@LaunchedEffect
        val (width, height) = measuredSize ?: return@LaunchedEffect
        val device = ArDevice.getInstance(session)
        val gate = HeadMotionGate()
        var stationarySince: Long? = null
        var reported = false
        while (!reported && !panel.isDisposed) {
            withFrameNanos { now ->
                val state = device.state.value
                val head = state.devicePose.takeIf { state.trackingState == TrackingState.TRACKING }
                val rotation = head?.rotation?.let {
                    HeadMotionGate.Rotation(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.w.toDouble())
                }
                val speed = gate.update(now, rotation)
                if (head == null || speed == null || speed >= HeadMotionGate.StationaryDegreesPerSecond) {
                    stationarySince = null
                } else {
                    val start = stationarySince ?: now.also { stationarySince = it }
                    if (now - start >= 250_000_000L) {
                        val inverseHead = scene.perceptionSpace.transformPoseTo(head, scene.activitySpace).inverse
                        val relative = inverseHead.compose(panel.getPose(Space.ACTIVITY))
                        val bounds = skyPitchBounds(relative, width, height)
                        val result = if (bounds.insideBand) "PASS" else "FAIL"
                        Log.i("AuraPlacement", "SKYBAND $result mark=$kind " +
                            "minPitch=${skyDegrees(bounds.min)} maxPitch=${skyDegrees(bounds.max)}")
                        reported = true
                    }
                }
            }
        }
    }
    SceneCoreEntity(
        factory = { Entity.create(session, "Aura sky $kind") },
        modifier = placement,
        update = { entity = it },
    ) {
        SpatialPanel(
            size.onGloballyPositioned { coordinates ->
                val pixels = scene.virtualPixelDensity
                val width = pixels.convertPixelsToMeters(coordinates.size.width.toFloat()) * scale
                val height = pixels.convertPixelsToMeters(coordinates.size.height.toFloat()) * scale
                if (width > 0f && height > 0f) measuredSize = width to height
            },
            shape = shape,
            content = content,
        )
    }
}
