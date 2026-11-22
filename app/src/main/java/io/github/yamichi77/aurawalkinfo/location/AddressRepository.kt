package io.github.yamichi77.aurawalkinfo.location

import io.github.yamichi77.aurawalkinfo.session.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

data class AddressParts(
    val prefecture: String? = null,
    val city: String? = null,
    val town: String? = null,
    val chome: String? = null,
)

sealed interface AddressState {
    data object Loading : AddressState
    data class Available(val parts: AddressParts, val stale: Boolean = false) : AddressState
    data object Failed : AddressState
}

fun interface ReverseGeocoder {
    suspend fun lookup(latitude: Double, longitude: Double): AddressParts?
}

class AddressRepository(
    private val geocoder: ReverseGeocoder,
    private val distanceCalculator: DistanceCalculator,
    private val clock: Clock,
) {
    fun observe(locations: Flow<LocationSample>): Flow<AddressState> = channelFlow {
        send(AddressState.Loading)
        var request: Job? = null
        var lastRequestedPoint: LocationSample? = null
        var lastRequestMillis = 0L
        var lastAddress: AddressParts? = null

        locations.collect { sample ->
            if (request?.isActive == true) return@collect

            val now = clock.elapsedRealtimeMillis()
            val previous = lastRequestedPoint
            if (previous != null &&
                now - lastRequestMillis < REFRESH_MILLIS &&
                distanceCalculator.metersBetween(previous, sample) < REFRESH_METERS
            ) return@collect

            lastRequestedPoint = sample
            lastRequestMillis = now
            request = launch {
                val result = geocoder.lookup(sample.latitude, sample.longitude)
                if (result != null) lastAddress = result
                val current = lastAddress
                send(
                    if (current == null) AddressState.Failed
                    else AddressState.Available(current, stale = result == null),
                )
            }
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 30_000L
        const val REFRESH_METERS = 50.0
    }
}
