package io.github.yamichi77.aurawalkinfo.nav

import java.time.Duration
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val PROJECTION_TIE_METERS = 0.001

private fun wrapLongitude(degrees: Double) = when {
    degrees > 180 -> degrees - 360
    degrees < -180 -> degrees + 360
    else -> degrees
}

internal fun NavPoint.distanceTo(other: NavPoint): Double {
    val latitudeDelta = Math.toRadians(other.latitude - latitude)
    val longitudeDelta = Math.toRadians(wrapLongitude(other.longitude - longitude))
    val haversine = sin(latitudeDelta / 2).let { it * it } +
        cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
        sin(longitudeDelta / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private fun NavPoint.bearingTo(other: NavPoint): Double? {
    val longitudeDelta = wrapLongitude(other.longitude - longitude)
    if (latitude == other.latitude && longitudeDelta == 0.0) return null
    val from = Math.toRadians(latitude)
    val to = Math.toRadians(other.latitude)
    val delta = Math.toRadians(longitudeDelta)
    val y = sin(delta) * cos(to)
    val x = cos(from) * sin(to) - sin(from) * cos(to) * cos(delta)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

internal class GuidanceCalculator(private val route: NavRoute, private val destination: NavPoint) {
    private data class Segment(val start: NavPoint, val end: NavPoint, val offset: Double) {
        val length = start.distanceTo(end)

        fun project(point: NavPoint, minimumProgress: Double, index: Int): Projection {
            // Project in a local metric plane. Walking segments are short enough for this approximation.
            val longitudeScale = cos(Math.toRadians((start.latitude + end.latitude) / 2))
            val longitudeDelta = wrapLongitude(end.longitude - start.longitude)
            val x = longitudeDelta * longitudeScale
            val y = end.latitude - start.latitude
            val squaredLength = x * x + y * y
            val minimumFraction = if (length == 0.0) 0.0 else
                ((minimumProgress - offset) / length).coerceIn(0.0, 1.0)
            val fraction = if (squaredLength == 0.0) 0.0 else (
                (wrapLongitude(point.longitude - start.longitude) * longitudeScale * x +
                    (point.latitude - start.latitude) * y) / squaredLength
                ).coerceIn(minimumFraction, 1.0)
            val projected = NavPoint(start.latitude + y * fraction, wrapLongitude(start.longitude + longitudeDelta * fraction))
            return Projection(projected, offset + length * fraction, point.distanceTo(projected), index)
        }
    }

    private data class Projection(val point: NavPoint, val progress: Double, val distance: Double, val segmentIndex: Int)
    private val segments: List<Segment>
    private val turnOffsets: List<Double>
    private val length: Double
    private var previousProgress = 0.0

    init {
        val built = mutableListOf<Segment>()
        var offset = 0.0
        fun append(from: NavPoint, to: NavPoint) {
            val segment = Segment(from, to, offset)
            built += segment
            offset += segment.length
        }

        // Step polylines preserve turns that can be simplified away in the overview.
        val detailed = route.steps.isNotEmpty() && route.steps.all { it.polyline.size >= 2 }
        val stepOffsets = mutableListOf<Double>()
        if (detailed) {
            route.steps.forEach { step ->
                built.lastOrNull()?.end?.let { append(it, step.start) }
                stepOffsets += offset
                val points = listOf(step.start) + step.polyline + step.end
                points.zipWithNext(::append)
            }
        } else {
            route.polyline.zipWithNext(::append)
        }
        segments = built
        length = offset
        turnOffsets = if (detailed) stepOffsets else buildList {
            for (step in route.steps) {
                val minimum = lastOrNull() ?: 0.0
                add(project(step.start, minimum, minimum).progress)
            }
        }
    }

    fun update(position: NavPoint): NavGuidance {
        val projection = project(position, 0.0, previousProgress)
        previousProgress = projection.progress
        val fraction = if (length == 0.0) 0.0 else projection.progress / length
        val metersPerGeometryMeter = if (length == 0.0) 0.0 else route.distanceMeters / length
        val next = turnOffsets.indices.firstOrNull { it > 0 && turnOffsets[it] > projection.progress + PROJECTION_TIE_METERS }
        return NavGuidance(
            positionOnRoute = projection.point,
            progressMeters = route.distanceMeters * fraction,
            distanceFromRouteMeters = projection.distance,
            nextTurn = next?.let {
                val step = route.steps[it]
                NavTurn(step, (turnOffsets[it] - projection.progress) * metersPerGeometryMeter, position.bearingTo(step.start))
            },
            remainingDistanceMeters = route.distanceMeters * (1 - fraction),
            remainingDuration = Duration.ofMillis((route.duration.toMillis() * (1 - fraction)).toLong()),
            destinationBearingDegrees = position.bearingTo(destination),
            remainingPolyline = buildList {
                add(projection.point)
                for (index in projection.segmentIndex until segments.size) {
                    val end = segments[index].end
                    if (last() != end) add(end)
                }
            },
        )
    }

    private fun project(point: NavPoint, minimum: Double, preferred: Double): Projection {
        var best: Projection? = null
        for ((index, segment) in segments.withIndex()) {
            if (segment.offset + segment.length < minimum) continue
            val candidate = segment.project(point, minimum, index)
            val current = best
            if (current == null || candidate.distance < current.distance - PROJECTION_TIE_METERS ||
                (abs(candidate.distance - current.distance) <= PROJECTION_TIE_METERS &&
                    comesNext(candidate, current, preferred))
            ) {
                best = candidate
            }
        }
        return checkNotNull(best)
    }

    private fun comesNext(candidate: Projection, current: Projection, previous: Double): Boolean {
        // An overlapping return leg has the same distance from the fix as the outbound leg.
        val candidateAhead = candidate.progress >= previous - PROJECTION_TIE_METERS
        val currentAhead = current.progress >= previous - PROJECTION_TIE_METERS
        return if (candidateAhead != currentAhead) candidateAhead
        else abs(candidate.progress - previous) < abs(current.progress - previous)
    }
}
