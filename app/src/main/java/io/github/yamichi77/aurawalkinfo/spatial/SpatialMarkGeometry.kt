package io.github.yamichi77.aurawalkinfo.spatial

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

private const val TURN_CLEARANCE_METERS = 0.6
internal const val DESTINATION_HEAD_ELEVATION_DEGREES = 15.0

/** Debug only: the pin elevation, set by `--ef pin_elevation` in markpreview to compare heights. */
internal var destinationHeadElevationDegrees = DESTINATION_HEAD_ELEVATION_DEGREES
private const val DESTINATION_TIP_CLEARANCE_METERS = 0.6
private const val DESTINATION_TOP_METERS = 40.0
private const val NEAR_ARROW_DISTANCE_METERS = 10.0
internal const val PIN_HEAD_RADIUS = 0.8
private const val PIN_HEIGHT = 2.4
internal const val STROKE_PADDING = 1.5

internal fun isNearTurn(distanceMeters: Double) = distanceMeters <= NEAR_ARROW_DISTANCE_METERS

internal fun arrowOpacity(near: Boolean) = if (near) 0.5f else 1f

private fun widthForAngle(distance: Double, degrees: Double) =
    2 * distance * tan(Math.toRadians(degrees / 2))

private val pinScalePerMeter = widthForAngle(1.0, 2.5) / (2 * PIN_HEAD_RADIUS)
private val pinTipDropPerMeter = (PIN_HEIGHT - PIN_HEAD_RADIUS) * pinScalePerMeter

private fun MarkVector.horizontal(up: MarkVector) = this - up * dot(up)

/** The panel's +Z faces the eye, while its +Y stays aligned with gravity. */
internal fun markPanelFacing(base: MarkVector, view: MarkView, fallback: MarkVector): MarkVector {
    val towardEye = (view.eye - base).horizontal(view.up)
    if (towardEye.length() > 1e-6) return towardEye
    // Directly underneath, keep the last facing in the current horizontal plane.
    val previous = fallback.horizontal(view.up)
    if (previous.length() > 1e-6) return previous
    val east = MarkVector(1.0, 0.0, 0.0)
    val perpendicular = east.cross(view.up)
    return if (perpendicular.length() > 1e-6) perpendicular else MarkVector.UP
}

internal fun turnBendDegrees(towardTurn: MarkVector, outgoing: MarkVector, up: MarkVector): Double {
    val angle = Math.toDegrees(atan2(outgoing.dot(towardTurn.cross(up)), outgoing.dot(towardTurn)))
    return when {
        abs(angle) < 20.0 -> 0.0
        abs(angle) > 150.0 -> if (angle < 0.0) -180.0 else 180.0
        else -> angle
    }
}

internal data class MarkPoint(val x: Double, val y: Double)

internal sealed interface MarkArtwork {
    val width: Double
    val height: Double
    val stroke: Double

    data class Arrow(
        val shaft: List<MarkPoint>,
        val head: List<MarkPoint>,
        override val width: Double,
        override val height: Double,
        override val stroke: Double,
        val near: Boolean,
    ) : MarkArtwork

    data class Destination(
        val pinScale: Double,
        val visibleHeight: Double,
        override val width: Double,
        override val stroke: Double,
    ) : MarkArtwork {
        private val padding = stroke * STROKE_PADDING
        val headY = padding + PIN_HEAD_RADIUS * pinScale
        val tipY = padding + PIN_HEIGHT * pinScale
        val tailEndY = tipY + PIN_HEIGHT * pinScale / 2
        override val height = tailEndY + padding
    }
}

internal data class StandingMark(
    val bottom: MarkVector,
    val facing: MarkVector,
    val artwork: MarkArtwork,
) {
    fun center(up: MarkVector) = bottom + up * (artwork.height / 2)
}

internal fun standingArrow(
    base: MarkVector,
    view: MarkView,
    facing: MarkVector,
    outgoing: MarkVector,
    near: Boolean,
): StandingMark {
    // Keep the ground position fixed while the lower edge follows the tracked eye height.
    val bottom = base + view.up * (TURN_CLEARANCE_METERS + (view.eye - base).dot(view.up))
    val bend = turnBendDegrees(facing * -1.0, outgoing, view.up)
    val angle = Math.toRadians(bend)
    val tip = if (abs(bend) == 180.0) {
        MarkPoint(if (bend < 0) -0.85 else 0.85, -0.3)
    } else {
        MarkPoint(0.95 * sin(angle), -1.0 - 0.95 * cos(angle))
    }
    val shaft = buildList {
        add(MarkPoint(0.0, 0.0))
        if (bend != 0.0) add(MarkPoint(0.0, -1.0))
        if (abs(bend) == 180.0) add(MarkPoint(tip.x, -1.0))
        add(tip)
    }
    val head = listOf(-1.0, 1.0).map { side ->
        MarkPoint(
            tip.x - 0.39 * sin(angle) + side * 0.43 * cos(angle),
            tip.y + 0.39 * cos(angle) + side * 0.43 * sin(angle),
        )
    }.let { (left, right) -> listOf(left, tip, right) }
    val points = shaft + head
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val pathWidth = points.maxOf { it.x } - minX
    val pathHeight = points.maxOf { it.y } - minY
    val stroke = pathWidth * 0.11
    val pad = stroke * STROKE_PADDING
    val width = pathWidth + 2 * pad
    val height = pathHeight + 2 * pad
    val bottomDistance = (bottom - view.eye).length()
    val widthPerMeter = widthForAngle(1.0, 5.0)
    // The top is at most one artwork height farther away than the bottom. Size the solid
    // stroke to meet the floor there too, and include the glow in the near width cap.
    val minScale = widthPerMeter * bottomDistance / (pathWidth + stroke - widthPerMeter * height)
    val maxScale = widthForAngle(bottomDistance, 8.0) / width
    val scale = if (near) minOf(minScale, maxScale) else minScale
    fun place(point: MarkPoint) = MarkPoint((point.x - minX + pad) * scale, (point.y - minY + pad) * scale)
    return StandingMark(
        bottom, facing,
        MarkArtwork.Arrow(shaft.map(::place), head.map(::place), width * scale, height * scale, stroke * scale, near),
    )
}

internal fun destinationHeadTarget(ground: MarkVector, view: MarkView): MarkVector {
    val offset = ground - view.eye
    val rise = offset.horizontal(view.up).length() * tan(Math.toRadians(destinationHeadElevationDegrees))
    return ground + view.up * (rise - offset.dot(view.up))
}

internal fun standingDestination(
    smoothedHead: MarkVector,
    ground: MarkVector,
    view: MarkView,
    facing: MarkVector,
): StandingMark? {
    val offset = smoothedHead - view.eye
    val horizontal = offset.horizontal(view.up).length()
    val preferredRise = offset.dot(view.up)
    // Raising the tip to its lower limit can increase the distance to the head.
    val distance = maxOf(offset.length(), hypot(horizontal, DESTINATION_TIP_CLEARANCE_METERS) / (1 - pinTipDropPerMeter))
    val pinScale = distance * pinScalePerMeter
    val stroke = widthForAngle(distance, 0.4)
    val pinWidth = 2 * PIN_HEAD_RADIUS * pinScale
    val width = pinWidth + 2 * stroke * STROKE_PADDING
    val artwork = MarkArtwork.Destination(pinScale, 0.0, width, stroke)
    val minimumHeadRise = DESTINATION_TIP_CLEARANCE_METERS + artwork.tipY - artwork.headY
    val maximumHeadRise = (ground - view.eye).dot(view.up) + DESTINATION_TOP_METERS - artwork.headY
    if (minimumHeadRise > maximumHeadRise) return null
    val headRise = preferredRise.coerceIn(minimumHeadRise, maximumHeadRise)
    val head = smoothedHead + view.up * (headRise - preferredRise)
    val top = head + view.up * artwork.headY
    // Clip across the full width, including the glow. The panel corners are farther from
    // the eye than the centerline, so they define the safe lower edge.
    val minimumRise = hypot(horizontal, width / 2) * tan(Math.toRadians(MIN_MARK_ELEVATION_DEGREES))
    val visibleHeight = minOf(artwork.height, (top - view.eye).dot(view.up) - minimumRise - 0.001)
    if (visibleHeight <= 0) return null
    return StandingMark(top - view.up * artwork.height, facing, artwork.copy(visibleHeight = visibleHeight))
}
