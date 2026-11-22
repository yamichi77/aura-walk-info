package io.github.yamichi77.aurawalkinfo.location

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeLocationRepository : LocationRepository {
    private val samples = MutableSharedFlow<LocationSample>()
    override val locations = samples.asSharedFlow()

    suspend fun emit(sample: LocationSample) {
        samples.emit(sample)
    }
}
