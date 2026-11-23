package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.ui.unit.dp
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal object HudPlacement {
    private const val yawSign = 1f
    // Compose XR rc01 inserts its view-following origin 0.5 m forward.
    private const val viewOriginForwardMeters = 0.5f

    fun pose(yaw: Float, pitch: Float, distance: Float = Layout.distanceMeters): Pose {
        val yawRadians = radians(yaw * yawSign)
        val pitchRadians = radians(pitch)
        val horizontal = distance * cos(pitchRadians)
        return Pose(
            Vector3(horizontal * sin(yawRadians), distance * sin(pitchRadians), -horizontal * cos(yawRadians)),
            rotation(yaw, pitch),
        )
    }

    fun rotation(yaw: Float, pitch: Float): Quaternion =
        Quaternion.fromAxisAngle(Vector3.Up, -yaw * yawSign) *
            Quaternion.fromAxisAngle(Vector3.Right, pitch)

    fun modifier(pose: Pose, dpPerMeter: Float, subspaceScale: Float): SubspaceModifier {
        // The SDK scales both its origin offset and the child offsets.
        val sdkDpPerMeter = dpPerMeter / subspaceScale
        val position = pose.translation
        return SubspaceModifier.offset(
            x = (position.x * sdkDpPerMeter).dp,
            y = (position.y * sdkDpPerMeter).dp,
            z = (position.z * sdkDpPerMeter + viewOriginForwardMeters * dpPerMeter).dp,
        ).rotate(pose.rotation)
    }

    private fun radians(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()

    private fun tangent(degrees: Float) = tan(radians(degrees))

    fun planeHalfSpan(degrees: Float) = Layout.distanceMeters * tangent(degrees)

    const val bigMapPitch = -4f
    const val bigMapDiameter = 28f
    val bigMapSizeMeters = 2 * planeHalfSpan(bigMapDiameter / 2)
    val countdownWidthMeters = 2 * planeHalfSpan(8f)
    val countdownHeightMeters = 2 * planeHalfSpan(7f)
    val countdownDigitHeightMeters = 2 * planeHalfSpan(2f)
    val bigMapArrowHeightMeters get() = mapArrowHeight(mapSizeMeters(Layout.walkingDiameter))

    // Allow for layout's integer pixel rounding at the inside edges.
    private const val roundingMarginDegrees = 0.08f
    private val maxInnerHalfYaw = Layout.rightYaw - Layout.clearZoneDegrees -
        Layout.clearGapDegrees - roundingMarginDegrees
    private val statsBelowAnchor = planeHalfSpan(5.9f)
    private val statsAboveAnchor = planeHalfSpan(7.8f)
    val statsHeightMeters = statsBelowAnchor + statsAboveAnchor
    val statsWidthMeters = 2 * (Layout.distanceMeters * cos(radians(Layout.pitch)) -
        statsBelowAnchor * sin(radians(-Layout.pitch))) * tangent(maxInnerHalfYaw)

    fun statsPose(): Pose {
        val anchor = pose(Layout.leftYaw, Layout.pitch)
        // Extend the panel upward along its plane, retaining the lower edge and anchor.
        val centerOffset = (statsAboveAnchor - statsBelowAnchor) / 2
        return Pose(anchor.translation + anchor.up * centerOffset, anchor.rotation)
    }

    fun mapSizeMeters(diameter: Float): Float {
        val halfYaw = minOf(diameter / 2, maxInnerHalfYaw)
        // Tangency to the vertical plane at the inside-edge yaw.
        val normalComponent = cos(radians(Layout.pitch)) * sin(radians(halfYaw))
        return 2 * Layout.distanceMeters * normalComponent / sqrt(1 - normalComponent * normalComponent)
    }
}
