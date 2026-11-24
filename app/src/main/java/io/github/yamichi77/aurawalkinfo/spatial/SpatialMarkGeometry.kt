package io.github.yamichi77.aurawalkinfo.spatial

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal const val MAX_MARK_DOTS = 200
internal const val MIN_DOT_ANGLE_DEGREES = 0.3
internal const val MIN_ARROW_ANGLE_DEGREES = 5.0
internal const val MAX_NEAR_ARROW_ANGLE_DEGREES = 8.0
internal const val NEAR_ARROW_DISTANCE_METERS = 10.0
private const val ARROW_RADIUS_METERS = 1.25
private const val ARROW_WIDTH_METERS = 2.0
private const val DOT_SIDES = 6
private const val OUTER_GLOW_SCALE = 1.9
private const val ARROW_DOTS_PER_SIDE = 11
private const val PILLAR_DOTS = 24
private const val PIN_ARC_DOTS = 13
private const val PIN_DOTS_PER_SIDE = 2
private val dotCircle = List(DOT_SIDES) { index ->
    val angle = index * 2.0 * Math.PI / DOT_SIDES
    cos(angle) to sin(angle)
}

internal fun markDotVertexCapacities(kind: MarkKind): List<Int> {
    val dotCount = when (kind) {
        MarkKind.TURN -> 2 * ARROW_DOTS_PER_SIDE
        MarkKind.DESTINATION -> PILLAR_DOTS + PIN_ARC_DOTS + 2 * PIN_DOTS_PER_SIDE + 2
        MarkKind.SIGNAL -> error("Signal totems use a panel")
    }
    // Each clipped glow quad can contribute up to four triangles, drawn in both windings.
    return List(3) { dotCount * DOT_SIDES * 4 * 3 * 2 }
}

private fun metersForAngle(distance: Double, angleDegrees: Double) =
    2.0 * distance * tan(Math.toRadians(angleDegrees) / 2.0)

// Use the inscribed circle for the 0.3° floor, including across the flats of the hexagon.
internal fun dotRadius(distance: Double) =
    metersForAngle(distance, MIN_DOT_ANGLE_DEGREES) / (2 * cos(Math.PI / DOT_SIDES))

internal fun isNearTurn(distanceMeters: Double) = distanceMeters <= NEAR_ARROW_DISTANCE_METERS

internal fun arrowOpacity(near: Boolean) = if (near) 0.5f else 1f

internal fun arrowScale(distance: Double, near: Boolean): Double {
    val widthPerMeter = metersForAngle(1.0, MIN_ARROW_ANGLE_DEGREES)
    val scale = maxOf(1.0, widthPerMeter * distance /
        (ARROW_WIDTH_METERS - widthPerMeter * ARROW_RADIUS_METERS))
    if (!near) return scale

    // Keep even the outer glow inside a sphere that subtends 8° at the eye.
    val usableRadius = distance * sin(Math.toRadians(MAX_NEAR_ARROW_ANGLE_DEGREES / 2))
    val glowPerMeter = dotRadius(1.0) * OUTER_GLOW_SCALE
    return minOf(scale, (usableRadius - distance * glowPerMeter) /
        (ARROW_RADIUS_METERS * (1 + glowPerMeter)))
}

internal fun markDots(
    kind: MarkKind,
    center: MarkVector,
    forward: MarkVector,
    view: MarkView,
    nowMillis: Long,
    near: Boolean = false,
): List<MarkVector> {
    val up = view.up
    val right = forward.cross(up)
    return when (kind) {
        MarkKind.TURN -> {
            val scale = arrowScale((center - view.eye).length(), near)
            if (scale <= 0.0) return emptyList()
            // Pitch the outline so its head remains readable from a low viewing angle.
            val pitch = Math.toRadians(30.0)
            val along = forward * cos(pitch) + up * sin(pitch)
            // Both sides travel along the outline from the back of the shaft to the tip.
            fun side(sign: Double, phase: Double): List<MarkVector> {
                val outline = listOf(0.0 to -1.0, 0.36 to -1.0, 0.36 to 0.15, 1.0 to 0.15, 0.0 to 1.2)
                val path = outline.map { (x, z) -> center + (right * (sign * x) + along * z) * scale }
                return flowingDots(path, ARROW_DOTS_PER_SIDE, nowMillis, phase)
            }
            side(1.0, 0.25) + side(-1.0, 0.75)
        }
        MarkKind.DESTINATION -> {
            val top = center + up * (40.0 - kind.baseHeight)
            // Match the T22 pin: a circular head, two tangent sides, and a center dot.
            val towardEye = view.eye - top
            val across = up.cross(towardEye).unitOr(right)
            val scale = maxOf(0.75, (top - view.eye).length() * 0.017)
            val tip = top - up * (2.4 * scale)
            val head = tip + up * (1.6 * scale)
            val radius = 0.8 * scale
            val tangent = acos(0.5)
            val arc = List(PIN_ARC_DOTS) { index ->
                val angle = tangent + index * (2 * Math.PI - 2 * tangent) / (PIN_ARC_DOTS - 1)
                head + across * (radius * sin(angle)) - up * (radius * cos(angle))
            }
            val sides = listOf(arc.first(), arc.last()).flatMap { end ->
                List(PIN_DOTS_PER_SIDE) { index -> tip.toward(end, (index + 1.0) / (PIN_DOTS_PER_SIDE + 1)) }
            }
            flowingDots(listOf(center, tip), PILLAR_DOTS, nowMillis) + arc + sides + listOf(tip, head)
        }
        MarkKind.SIGNAL -> error("Signal totems use a panel")
    }
}

internal fun flowingDots(
    path: List<MarkVector>, count: Int, nowMillis: Long, phase: Double = 0.0,
): List<MarkVector> {
    val lengths = path.zipWithNext { start, end -> (end - start).length() }
    val spacing = lengths.sum() / count
    // Advancing one metre per second and wrapping one spacing keeps the number of dots fixed.
    val offset = (nowMillis / 1_000.0 + phase * spacing) % spacing
    var segment = 0
    var passed = 0.0
    return List(count) { index ->
        val distance = index * spacing + offset
        while (segment < lengths.lastIndex && distance >= passed + lengths[segment]) {
            passed += lengths[segment]
            segment++
        }
        path[segment].toward(path[segment + 1], (distance - passed) / lengths[segment])
    }
}

internal fun dotTriangles(dots: List<MarkVector>, view: MarkView): List<List<MarkVector>> {
    check(dots.size <= MAX_MARK_DOTS)
    val layers = List(3) { mutableListOf<MarkVector>() }
    for (dot in dots) {
        val towardEye = view.eye - dot
        val radius = dotRadius(towardEye.length())
        if (radius == 0.0 || view.margin(dot) < -radius * OUTER_GLOW_SCALE * 1.1) continue
        val facing = towardEye.unitOr(view.up)
        val across = view.up.cross(facing).unitOr(MarkVector(1.0, 0.0, 0.0))
        val vertical = facing.cross(across).unitOr(view.up)
        val offsets = dotCircle.map { (x, y) -> across * (x * radius) + vertical * (y * radius) }
        val (core, inner, outer) = listOf(1.0, 1.45, OUTER_GLOW_SCALE).map { size ->
            offsets.map { dot + it * size }
        }
        layers[0].addClippedFace(view, core)
        for (index in core.indices) {
            val next = (index + 1) % DOT_SIDES
            layers[1].addClippedFace(view, listOf(core[index], inner[index], inner[next], core[next]))
            layers[2].addClippedFace(view, listOf(inner[index], outer[index], outer[next], inner[next]))
        }
    }
    return layers
}

private fun MutableList<MarkVector>.addClippedFace(view: MarkView, corners: List<MarkVector>) {
    // Keep a small inset so converting the vertices to floats preserves the elevation limit.
    val inset = 0.0001
    val margins = corners.map { view.margin(it) - inset }
    val clipped = if (margins.all { it >= 0.0 }) corners else buildList {
        for (index in corners.indices) {
            val next = (index + 1) % corners.size
            val startInside = margins[index] >= 0.0
            if (startInside) add(corners[index])
            if (startInside == (margins[next] >= 0.0)) continue

            var inside = if (startInside) corners[index] else corners[next]
            var outside = if (startInside) corners[next] else corners[index]
            repeat(16) {
                val middle = inside.toward(outside, 0.5)
                if (view.margin(middle) >= inset) inside = middle else outside = middle
            }
            add(inside)
        }
    }
    // The visible region is convex, so triangles between inside vertices stay above it.
    for (index in 1 until clipped.lastIndex) {
        val a = clipped[0]
        val b = clipped[index]
        val c = clipped[index + 1]
        add(a); add(b); add(c)
        add(a); add(c); add(b)
    }
}

private fun MarkVector.unitOr(fallback: MarkVector): MarkVector {
    val size = length()
    return if (size > 1e-6) this * (1.0 / size) else fallback
}
