package io.github.yamichi77.aurawalkinfo.location

import io.github.yamichi77.aurawalkinfo.session.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddressRepositoryTest {
    @Test
    fun refreshesAtThirtySecondsOrFiftyMetersWithoutOverlappingRequests() = runTest {
        val locations = FakeLocationRepository()
        val requests = mutableListOf<CompletableDeferred<AddressParts?>>()
        val repo = AddressRepository(
            ReverseGeocoder { _, _ ->
                CompletableDeferred<AddressParts?>().also(requests::add).await()
            },
            haversine,
            Clock { testScheduler.currentTime },
        )
        val states = mutableListOf<AddressState>()
        backgroundScope.launch { repo.observe(locations.locations).collect(states::add) }
        runCurrent()
        assertEquals(AddressState.Loading, states.last())

        suspend fun sample(meters: Double) {
            locations.emit(syntheticFix(testScheduler.currentTime, meters))
            runCurrent()
        }

        sample(0.0)
        sample(100.0)
        assertEquals(1, requests.size)
        val parts = AddressParts(prefecture = "東京都", city = "架空区", town = "架空町")
        requests[0].complete(parts)
        runCurrent()
        assertEquals(AddressState.Available(parts), states.last())

        advanceTimeBy(29_999)
        sample(49.9)
        assertEquals(1, requests.size)
        advanceTimeBy(1)
        sample(0.0)
        assertEquals(2, requests.size)
        sample(100.0)
        assertEquals(2, requests.size)
        requests[1].complete(null)
        runCurrent()
        assertEquals(AddressState.Available(parts, stale = true), states.last())

        sample(50.1)
        assertEquals(3, requests.size)
        val newParts = parts.copy(chome = "2丁目")
        requests[2].complete(newParts)
        runCurrent()
        assertEquals(AddressState.Available(newParts), states.last())
    }

    @Test
    fun reportsFailedWhenFirstLookupReturnsNoAddress() = runTest {
        val locations = FakeLocationRepository()
        val repo = AddressRepository(ReverseGeocoder { _, _ -> null }, haversine, Clock { 0L })
        val states = mutableListOf<AddressState>()
        backgroundScope.launch { repo.observe(locations.locations).collect(states::add) }
        runCurrent()

        locations.emit(syntheticFix(0))
        runCurrent()
        assertEquals(listOf(AddressState.Loading, AddressState.Failed), states)
    }
}
