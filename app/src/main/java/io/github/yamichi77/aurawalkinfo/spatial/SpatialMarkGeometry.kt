package io.github.yamichi77.aurawalkinfo.spatial

import kotlin.math.cos
import kotlin.math.sin

internal data class MarkStroke(val start: MarkVector, val end: MarkVector, val width: Double)

internal fun markStrokes(
    kind: MarkKind,
    center: MarkVector,
    forward: MarkVector,
    up: MarkVector,
    signalDots: Int,
): List<List<MarkStroke>> {
    val right = forward.cross(up)
    fun point(x: Double, y: Double, z: Double) = center + right * x + up * y + forward * z
    fun loop(points: List<MarkVector>, width: Double) =
        points.mapIndexed { index, start -> MarkStroke(start, points[(index + 1) % points.size], width) }

    fun ring(horizontal: MarkVector, vertical: MarkVector, origin: MarkVector, radius: Double, width: Double, steps: Int) =
        loop(List(steps) { index ->
            val angle = index * 2.0 * Math.PI / steps
            origin + horizontal * (radius * cos(angle)) + vertical * (radius * sin(angle))
        }, width)

    return when (kind) {
        MarkKind.TURN -> {
            val outline = listOf(
                0.0 to 1.2, 1.0 to 0.15, 0.36 to 0.15, 0.36 to -1.0,
                -0.36 to -1.0, -0.36 to 0.15, -1.0 to 0.15,
            )
            val top = outline.map { (x, z) -> point(x, 0.18, z) }
            val bottom = outline.map { (x, z) -> point(x, -0.18, z) }
            listOf(loop(top, 0.035) + loop(bottom, 0.035) + top.zip(bottom) { a, b -> MarkStroke(a, b, 0.035) })
        }
        MarkKind.DESTINATION -> {
            val top = center + up * (40.0 - kind.baseHeight)
            listOf(listOf(MarkStroke(center, top, 0.3)) + ring(right, forward, top, 0.6, 0.06, 16))
        }
        MarkKind.SIGNAL -> buildList {
            add(ring(right, up, center, 0.5, 0.025, 16))
            val columns = 4
            val rows = (signalDots + columns - 1) / columns
            repeat(signalDots) { index ->
                val row = index / columns
                val rowSize = minOf(columns, signalDots - row * columns)
                val x = (index % columns - (rowSize - 1) / 2.0) * 0.16
                val y = ((rows - 1) / 2.0 - row) * 0.16
                add(ring(right, up, point(x, y, 0.0), 0.05, 0.026, 8))
            }
        }
    }
}

// The visible cone is convex. Joining inside points by chords cannot draw below its boundary.
internal fun MarkView.clip(
    start: MarkVector,
    end: MarkVector,
    minimumMargin: Double = -1e-12,
): Pair<MarkVector, MarkVector>? {
    fun at(t: Double) = start.toward(end, t)
    fun marginAt(t: Double) = margin(at(t)) - minimumMargin
    val startMargin = marginAt(0.0)
    val endMargin = marginAt(1.0)
    if (startMargin >= 0.0 && endMargin >= 0.0) return start to end

    val inside = when {
        startMargin >= 0.0 -> 0.0
        endMargin >= 0.0 -> 1.0
        else -> {
            // Both ends can be outside while the middle passes closer to the eye.
            var left = 0.0
            var right = 1.0
            repeat(24) {
                val first = (2 * left + right) / 3
                val second = (left + 2 * right) / 3
                if (marginAt(first) < marginAt(second)) left = first else right = second
            }
            ((left + right) / 2).takeIf { marginAt(it) >= 0.0 } ?: return null
        }
    }

    fun boundary(outside: Double): Double {
        var visible = inside
        var hidden = outside
        repeat(22) {
            val midpoint = (visible + hidden) / 2
            if (marginAt(midpoint) >= 0.0) visible = midpoint else hidden = midpoint
        }
        return visible
    }
    return at(if (startMargin >= 0.0) 0.0 else boundary(0.0)) to
        at(if (endMargin >= 0.0) 1.0 else boundary(1.0))
}

// Two crossed ribbons keep a stroke visible from different viewpoints. Their interiors
// depict only the stroke width; there are no faces across the arrow, rings, or signal dots.
internal fun MarkStroke.clippedTriangles(view: MarkView, up: MarkVector, right: MarkVector): List<MarkVector> {
    val direction = end - start
    // The margin can change by at most 1 + tan(5°) per meter.
    if (view.margin(start.toward(end, 0.5)) < -(direction.length() + width) * 0.55) return emptyList()
    val perpendicular = direction.cross(up).takeIf { it.length() > 1e-6 } ?: direction.cross(right)
    val across = perpendicular * (1.0 / perpendicular.length())
    val other = direction.cross(across)
    return buildList {
        for (axis in listOf(across, other * (1.0 / other.length()))) {
            val offset = axis * (width / 2)
            val corners = listOf(start - offset, end - offset, end + offset, start + offset)
            val clipped = corners.indices.flatMap { index ->
                // Inset slightly to keep Float conversion from pushing a vertex below 5°.
                val segment = view.clip(corners[index], corners[(index + 1) % corners.size], minimumMargin = 0.0001)
                if (segment == null) emptyList() else listOf(segment.first, segment.second)
            }.distinct()
            for (index in 1 until clipped.lastIndex) {
                val a = clipped[0]
                val b = clipped[index]
                val c = clipped[index + 1]
                add(a); add(b); add(c)
                add(a); add(c); add(b)
            }
        }
    }
}
