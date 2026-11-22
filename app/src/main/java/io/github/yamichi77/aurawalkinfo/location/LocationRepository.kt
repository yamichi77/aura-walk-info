package io.github.yamichi77.aurawalkinfo.location

import kotlinx.coroutines.flow.Flow

data class LocationSample(
    val elapsedRealtimeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
)

interface LocationRepository {
    val locations: Flow<LocationSample>
}

fun interface DistanceCalculator {
    fun metersBetween(from: LocationSample, to: LocationSample): Double
}
