package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceMeasurable
import androidx.xr.compose.subspace.layout.SubspaceMeasureResult
import androidx.xr.compose.subspace.layout.SubspaceMeasureScope
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.onGloballyPositioned
import androidx.xr.compose.subspace.node.SubspaceLayoutModifierNode
import androidx.xr.compose.subspace.node.SubspaceModifierNodeElement
import androidx.xr.compose.unit.VolumeConstraints
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

internal fun skyPanelPlacement(dpPerMeter: Float, scale: Float, pose: () -> Pose): SubspaceModifier =
    SubspaceModifier then SkyPlacementElement(dpPerMeter, scale, pose)

private data class SkyPlacementElement(
    val dpPerMeter: Float,
    val scale: Float,
    val pose: () -> Pose,
) : SubspaceModifierNodeElement<SkyPlacementNode>() {
    override fun create() = SkyPlacementNode(this)
    override fun update(node: SkyPlacementNode) { node.placement = this }
}

private class SkyPlacementNode(var placement: SkyPlacementElement) :
    SubspaceModifier.Node(), SubspaceLayoutModifierNode {
    override fun SubspaceMeasureScope.measure(
        measurable: SubspaceMeasurable,
        constraints: VolumeConstraints,
    ): SubspaceMeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height, child.depth) {
            // rc01 observes snapshot reads in placement. Match offset().rotate(), including dp rounding.
            val pose = placement.pose()
            val dp = HudPlacement.translationInDp(pose.translation, placement.dpPerMeter, placement.scale)
            val x = dp.x.dp.roundToPx().toFloat()
            val pixels = Vector3(
                if (layoutDirection == LayoutDirection.Rtl) -x else x,
                dp.y.dp.roundToPx().toFloat(),
                dp.z.dp.roundToPx().toFloat(),
            )
            child.place(Pose(pixels, pose.rotation))
        }
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
    slot: SkyBandSlot,
    placement: SubspaceModifier,
    size: SubspaceModifier,
    shape: SpatialRoundedCornerShape,
    scale: Float,
    selfCheck: Boolean,
    content: @Composable () -> Unit,
) {
    val scene = session.scene
    val enabled by remember(slot) { derivedStateOf { slot.mark.value != null } }
    val factory = remember(session, slot) {
        { Entity.create(session, "Aura sky ${slot.kind}").apply { setEnabled(false) } }
    }
    var entity by remember { mutableStateOf<Entity?>(null) }
    var measuredSize by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    if (selfCheck) CheckSkyPanelPlacement(session, entity, measuredSize, slot.mark)
    SceneCoreEntity(
        factory = factory,
        modifier = placement,
        update = {
            if (it.isEnabled(includeParents = false) != enabled) it.setEnabled(enabled)
            if (selfCheck) entity = it
        },
    ) {
        val measured = if (selfCheck) size.onGloballyPositioned { coordinates ->
            val pixels = scene.virtualPixelDensity
            val width = pixels.convertPixelsToMeters(coordinates.size.width.toFloat()) * scale
            val height = pixels.convertPixelsToMeters(coordinates.size.height.toFloat()) * scale
            if (width > 0f && height > 0f) measuredSize = width to height
        } else size
        SpatialPanel(measured, shape = shape, content = content)
    }
}

@Composable
private fun CheckSkyPanelPlacement(
    session: Session,
    entity: Entity?,
    measuredSize: Pair<Float, Float>?,
    mark: State<SkyMark?>,
) {
    val kind by remember(mark) { derivedStateOf { mark.value?.kind } }
    LaunchedEffect(session, entity, measuredSize, kind) {
        val visibleKind = kind ?: return@LaunchedEffect
        val panel = entity ?: return@LaunchedEffect
        val (width, height) = measuredSize ?: return@LaunchedEffect
        val scene = session.scene
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
                        Log.i("AuraPlacement", "SKYBAND $result mark=$visibleKind " +
                            "minPitch=${skyDegrees(bounds.min)} maxPitch=${skyDegrees(bounds.max)}")
                        reported = true
                    }
                }
            }
        }
    }
}
