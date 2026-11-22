package io.github.yamichi77.aurawalkinfo.diagnostics.replay

import io.github.yamichi77.aurawalkinfo.location.LocationRepository
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class FakeLocationRepository : LocationRepository {
    private val samples = MutableSharedFlow<LocationSample>()
    override val locations = samples.asSharedFlow()

    suspend fun emit(sample: LocationSample) {
        samples.emit(sample)
    }
}
