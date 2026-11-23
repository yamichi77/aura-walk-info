package io.github.yamichi77.aurawalkinfo.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

data class LocationSample(
    val elapsedRealtimeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
)

interface LocationRepository {
    val locations: Flow<LocationSample>

    /** A null event advances timeout checks without supplying a fix. */
    fun sessionEvents(ticks: Flow<Unit>): Flow<LocationSample?> = merge(locations, ticks.map { null })
}

fun interface DistanceCalculator {
    fun metersBetween(from: LocationSample, to: LocationSample): Double
}
