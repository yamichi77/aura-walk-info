package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.FakeLocationRepository
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.haversine
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import io.github.yamichi77.aurawalkinfo.location.AddressRepository
import io.github.yamichi77.aurawalkinfo.location.ReverseGeocoder
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalkSessionRepositoryTest {
    @Test
    fun tracksWallTimeAndDoesNotAddWalkingTimeWhileStoppedFastOrWithoutFix() = runTest {
        val locations = FakeLocationRepository()
        val repo = startSession(locations)

        suspend fun fix(speed: Float) {
            locations.emit(syntheticFix(testScheduler.currentTime, speed = speed))
            runCurrent()
        }

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(MotionState.NO_FIX, repo.state.value.motionState)
        assertEquals(0L, repo.state.value.walkingTimeMillis)
        assertEquals(2_000L, repo.state.value.elapsedMillis)

        fix(1f)
        repeat(2) {
            advanceTimeBy(1_000)
            fix(1f)
        }
        assertEquals(2_000L, repo.state.value.walkingTimeMillis)

        repeat(9) {
            advanceTimeBy(1_000)
            fix(0f)
        }
        val stopped = repo.state.value
        assertEquals(MotionState.STOPPED, stopped.motionState)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(stopped.walkingTimeMillis, repo.state.value.walkingTimeMillis)
        assertEquals(stopped.elapsedMillis + 2_000, repo.state.value.elapsedMillis)

        repeat(10) {
            advanceTimeBy(1_000)
            fix(5f)
        }
        val fast = repo.state.value
        assertEquals(MotionState.FAST, fast.motionState)
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(MotionState.FAST, repo.state.value.motionState)
        assertEquals(fast.walkingTimeMillis, repo.state.value.walkingTimeMillis)
        assertEquals(fast.elapsedMillis + 11_000, repo.state.value.elapsedMillis)
    }

    @Test
    fun lostFixFreezesWalkTimeAndDropsAnchorBeforeReacquiring() = runTest {
        val locations = FakeLocationRepository()
        val repo = startSession(locations)

        advanceTimeBy(500)
        locations.emit(syntheticFix(500))
        runCurrent()
        advanceTimeBy(12_500)
        runCurrent()
        assertEquals(MotionState.NO_FIX, repo.state.value.motionState)
        assertEquals(10_000L, repo.state.value.walkingTimeMillis)
        assertEquals(13_000L, repo.state.value.elapsedMillis)

        locations.emit(syntheticFix(13_000, 50.0))
        runCurrent()
        assertEquals(MotionState.WALKING, repo.state.value.motionState)
        assertEquals(0.0, repo.state.value.distanceMeters, 0.0)
        advanceTimeBy(1_000)
        locations.emit(syntheticFix(14_000, 60.0))
        runCurrent()
        assertEquals(10.0, repo.state.value.distanceMeters, 0.001)
    }

    @Test
    fun bearingUsesMovingFixAndKeepsTheLastValueWhenSlowOrMissing() = runTest {
        val locations = FakeLocationRepository()
        val repo = startSession(locations)
        assertNull(repo.state.value.mapBearingDeg)

        locations.emit(syntheticFix(0, speed = 0.4f, bearing = 10f))
        runCurrent()
        assertNull(repo.state.value.mapBearingDeg)
        advanceTimeBy(1_000)
        locations.emit(syntheticFix(1_000, speed = 0.5f, bearing = 90f))
        runCurrent()
        assertEquals(90f, repo.state.value.mapBearingDeg)

        advanceTimeBy(1_000)
        locations.emit(syntheticFix(2_000, speed = 0f, bearing = 180f))
        runCurrent()
        assertEquals(90f, repo.state.value.mapBearingDeg)
        advanceTimeBy(1_000)
        val sample = syntheticFix(3_000, northMeters = 3.0, accuracy = 8f)
        locations.emit(sample)
        runCurrent()
        assertEquals(90f, repo.state.value.mapBearingDeg)
        assertEquals(MapCenter(sample.latitude, sample.longitude), repo.state.value.mapCenter)
        assertEquals(8f, repo.state.value.lastFixAccuracyMeters)
    }

    private fun TestScope.startSession(locations: FakeLocationRepository): WalkSessionRepository {
        val clock = Clock { testScheduler.currentTime }
        val addresses = AddressRepository(ReverseGeocoder { _, _ -> null }, haversine, clock)
        val repo = WalkSessionRepository(locations, addresses, haversine, clock)
        val session = repo.start(backgroundScope)
        assertSame(session, repo.start(backgroundScope))
        runCurrent()
        return repo
    }
}
