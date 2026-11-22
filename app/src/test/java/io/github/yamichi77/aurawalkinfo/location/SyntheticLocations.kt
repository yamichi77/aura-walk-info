package io.github.yamichi77.aurawalkinfo.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

internal val haversine = DistanceCalculator { from, to ->
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLatitude = lat2 - lat1
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
    2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal fun syntheticFix(
    millis: Long,
    northMeters: Double = 0.0,
    accuracy: Float = 5f,
    speed: Float? = 1.3f,
    bearing: Float? = null,
): LocationSample = LocationSample(
    millis,
    Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
    0.0,
    accuracy,
    speed,
    bearing,
)
