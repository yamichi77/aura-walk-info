package io.github.yamichi77.aurawalkinfo.pics

import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EARTH_RADIUS_METERS = 6_371_000.0
private const val FRESH_MILLIS = 1_500L
private const val SIGNAL_MILLIS = 3_000L
private const val POSITION_MILLIS = 60_000L

internal enum class PicsFreshness { FRESH, STALE }

internal data class PicsCandidate(
    val intersectionId: String,
    val position: MapCenter,
    val lights: List<PicsLight>,
    val freshness: PicsFreshness,
    val bearingDeg: Double,
    val distanceMeters: Double,
)

internal class PicsTracker(private val clock: Clock) {
    private data class Reception<out T>(val value: T, val at: Long, val rssi: Int) {
        fun validAt(now: Long, lifetime: Long) = now - at in 0..lifetime
    }

    private class Intersection {
        var position: Reception<MapCenter>? = null
        var signal: Reception<List<PicsLight>>? = null
        var identifier: Reception<String>? = null
    }

    private val intersections = mutableMapOf<String, Intersection>()

    fun receive(packet: PicsPacket, rssi: Int, at: Long = clock.elapsedRealtimeMillis()) {
        val intersection = intersections.getOrPut(packet.intersectionId, ::Intersection)
        when (packet) {
            is PicsPacket.Position ->
                if (at >= (intersection.position?.at ?: Long.MIN_VALUE)) {
                    intersection.position = Reception(packet.point, at, rssi)
                }
            is PicsPacket.Signal ->
                if (at >= (intersection.signal?.at ?: Long.MIN_VALUE)) {
                    intersection.signal = Reception(packet.lights, at, rssi)
                }
            is PicsPacket.Identifier ->
                if (at >= (intersection.identifier?.at ?: Long.MIN_VALUE)) {
                    intersection.identifier = Reception(packet.text, at, rssi)
                }
        }
    }

    fun nearest(motion: MotionState, current: MapCenter?): PicsCandidate? {
        if (motion != MotionState.STOPPED || current == null) return null
        if (current.latitude !in -90.0..90.0 || current.longitude !in -180.0..180.0) return null
        val now = clock.elapsedRealtimeMillis()
        return intersections.asSequence().mapNotNull { (id, intersection) ->
            val position = intersection.position?.takeIf { it.validAt(now, POSITION_MILLIS) }
                ?: return@mapNotNull null
            val signal = intersection.signal?.takeIf { it.validAt(now, SIGNAL_MILLIS) }
                ?: return@mapNotNull null
            val (distance, bearing) = distanceAndBearing(current, position.value)
            if (distance > 30.0) return@mapNotNull null
            val freshness = if (now - signal.at < FRESH_MILLIS) PicsFreshness.FRESH else PicsFreshness.STALE
            PicsCandidate(id, position.value, signal.value, freshness, bearing, distance)
        }.minByOrNull { it.distanceMeters }
    }

    /** Drops expired data and returns the next time an observation can change without a new packet. */
    fun nextUpdateAt(): Long? {
        val now = clock.elapsedRealtimeMillis()
        var next: Long? = null
        fun consider(at: Long?) {
            if (at != null && at > now && at < (next ?: Long.MAX_VALUE)) next = at
        }
        intersections.values.removeAll { intersection ->
            intersection.position = intersection.position?.takeIf { it.validAt(now, POSITION_MILLIS) }
            intersection.signal = intersection.signal?.takeIf { it.validAt(now, SIGNAL_MILLIS) }
            intersection.identifier = intersection.identifier?.takeIf { it.validAt(now, POSITION_MILLIS) }
            consider(intersection.position?.let { it.at + POSITION_MILLIS + 1 })
            consider(intersection.signal?.let { it.at + FRESH_MILLIS })
            consider(intersection.signal?.let { it.at + SIGNAL_MILLIS + 1 })
            consider(intersection.identifier?.let { it.at + POSITION_MILLIS + 1 })
            intersection.position == null && intersection.signal == null && intersection.identifier == null
        }
        return next
    }
}

private fun distanceAndBearing(from: MapCenter, to: MapCenter): Pair<Double, Double> {
    val latitude = Math.toRadians(from.latitude)
    val targetLatitude = Math.toRadians(to.latitude)
    val deltaLatitude = targetLatitude - latitude
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLatitude / 2).let { it * it } +
        cos(latitude) * cos(targetLatitude) * sin(deltaLongitude / 2).let { it * it }
    val distance = 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    val y = sin(deltaLongitude) * cos(targetLatitude)
    val x = cos(latitude) * sin(targetLatitude) - sin(latitude) * cos(targetLatitude) * cos(deltaLongitude)
    val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    return distance to bearing
}
