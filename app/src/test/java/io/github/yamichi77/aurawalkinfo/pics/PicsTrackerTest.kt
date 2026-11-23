package io.github.yamichi77.aurawalkinfo.pics

import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PicsTrackerTest {
    private var now = 0L
    private val tracker = PicsTracker(Clock { now })
    private val origin = MapCenter(0.0, 0.0)
    private val lights = listOf(PicsLight(PicsAspect.RED, 3))

    private fun degrees(meters: Double) = Math.toDegrees(meters / EARTH_RADIUS_METERS)

    private fun position(id: String, north: Double, east: Double = 0.0) {
        tracker.receive(PicsPacket.Position(id, MapCenter(degrees(north), degrees(east))), -62)
    }

    private fun signal(id: String) = tracker.receive(PicsPacket.Signal(id, lights), -60)

    private fun candidate(motion: MotionState = MotionState.STOPPED, point: MapCenter? = origin) =
        tracker.nearest(motion, point)

    @Test
    fun signalFreshnessChangesAtFifteenHundredAndExpiresAfterThreeThousandMillis() {
        position("DEADBEEF", 10.0)
        signal("DEADBEEF")
        assertEquals(1_500L, tracker.nextUpdateAt())
        now = 1_499
        assertEquals(PicsFreshness.FRESH, candidate()?.freshness)
        now = 1_500
        assertEquals(PicsFreshness.STALE, candidate()?.freshness)
        assertEquals(3_001L, tracker.nextUpdateAt())

        now = 3_000
        tracker.receive(PicsPacket.Identifier("DEADBEEF", "test"), -57)
        assertEquals(PicsFreshness.STALE, candidate()?.freshness)
        now = 3_001
        assertNull(candidate())
    }

    @Test
    fun positionLivesForSixtySecondsIndependentlyOfSignalUpdates() {
        position("DEADBEEF", 10.0)
        now = 60_000
        signal("DEADBEEF")
        assertEquals("DEADBEEF", candidate()?.intersectionId)
        assertEquals(60_001L, tracker.nextUpdateAt())
        now = 60_001
        assertNull(candidate())
        tracker.nextUpdateAt()
        position("DEADBEEF", 10.0)
        assertEquals("DEADBEEF", candidate()?.intersectionId)
    }

    @Test
    fun returnsOnlyForStoppedMotionWithCurrentPositionAndAnIntersectionWithinThirtyMeters() {
        position("00000001", 31.0)
        signal("00000001")
        signal("00000002")
        assertNull(candidate())

        position("00000003", 29.0)
        signal("00000003")
        assertEquals("00000003", candidate()?.intersectionId)
        assertEquals(29.0, candidate()!!.distanceMeters, 1e-6)
        assertEquals(lights, candidate()?.lights)
        assertNull(candidate(MotionState.WALKING))
        assertNull(candidate(MotionState.FAST))
        assertNull(candidate(MotionState.NO_FIX))
        assertNull(candidate(point = null))
    }

    @Test
    fun selectsNearestEligibleIntersectionAcrossCardinalBearings() {
        position("00000001", 29.0)
        signal("00000001")
        signal("DEADBEEF")

        val directions = listOf(
            Triple(10.0, 0.0, 0.0),
            Triple(0.0, 10.0, 90.0),
            Triple(-10.0, 0.0, 180.0),
            Triple(0.0, -10.0, 270.0),
        )
        directions.forEach { (north, east, expected) ->
            position("DEADBEEF", north, east)
            assertEquals("DEADBEEF", candidate()?.intersectionId)
            assertEquals(expected, candidate()!!.bearingDeg, 1e-6)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun replaysSyntheticPacketsThroughTheParserAndDisplayGate() = runTest {
        val parser = PicsParser(debuggable = true)
        val state = WalkSessionState(motionState = MotionState.STOPPED, mapCenter = origin)
        val replayTracker = PicsTracker(Clock { testScheduler.currentTime })
        val seen = mutableListOf<PicsLight?>()
        PicsReplay.RED_TO_GREEN.play(flowOf(state)) { bytes ->
            val packet = requireNotNull(parser.parse(TEST_COMPANY_ID, bytes))
            replayTracker.receive(packet, -60)
            if (packet is PicsPacket.Signal) {
                seen.add(replayTracker.nearest(state.motionState, origin)?.lights?.single())
            }
        }
        assertEquals(
            listOf(
                PicsLight(PicsAspect.RED, 3), PicsLight(PicsAspect.RED, 2), PicsLight(PicsAspect.RED, 1),
                PicsLight(PicsAspect.FLASHING_GREEN, 1), PicsLight(PicsAspect.GREEN, 8),
            ),
            seen,
        )
        assertEquals(10.0, replayTracker.nearest(state.motionState, origin)!!.distanceMeters, 0.1)

        val farTracker = PicsTracker(Clock { testScheduler.currentTime })
        PicsReplay.FAR.play(flowOf(state)) { bytes ->
            farTracker.receive(requireNotNull(parser.parse(TEST_COMPANY_ID, bytes)), -60)
        }
        assertNull(farTracker.nearest(state.motionState, origin))
    }
}
