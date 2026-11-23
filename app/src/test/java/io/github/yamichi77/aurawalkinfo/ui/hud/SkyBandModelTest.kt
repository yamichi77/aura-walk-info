package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.nav.NavGuidance
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavStep
import io.github.yamichi77.aurawalkinfo.nav.NavTurn
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkyBandModelTest {
    @Test
    fun samplerReadsOnlyWhileTrackingWithEnabledTargetsOutsideFastMotion() = runTest {
        val guidance = SkyBandTargets(skyTestGuidance(0.0, 10.0), null)
        val signal = SkyBandTargets(null, skyTestSignal(4.0))
        val empty = SkyBandTargets(null, null)
        var reads = 0
        suspend fun sample(fast: Boolean, tracking: Boolean, enabled: Boolean, targets: SkyBandTargets) {
            sampleSkyBand(
                skyBandSamplingActive(fast, tracking, enabled, targets),
                read = {
                    reads++
                    SkySample(targets, NorthEstimate(0.0, 5.0), 0.0)
                },
                publish = {},
            )
        }
        val blocked = listOf(
            backgroundScope.launch { sample(false, true, true, empty) },
            backgroundScope.launch { sample(false, true, false, guidance) },
            backgroundScope.launch { sample(true, true, true, guidance) },
            backgroundScope.launch { sample(false, false, true, guidance) },
        )
        runCurrent()
        assertEquals(0, reads)
        assertTrue(blocked.all { it.isCompleted })

        for (targets in listOf(guidance, signal)) {
            val before = reads
            val active = backgroundScope.launch { sample(false, true, true, targets) }
            advanceTimeBy(150)
            assertTrue(reads > before + 1)
            active.cancel()
            val stoppedAt = reads
            advanceTimeBy(150)
            assertEquals(stoppedAt, reads)
        }
    }

    @Test
    fun samplerPublishesAtHalfADegreeFromLastUpdateOrWhenTargetsChange() = runTest {
        val targets = SkyBandTargets(skyTestGuidance(0.0, 10.0), null)
        val initial = SkySample(targets, NorthEstimate(0.0, 5.0), 359.75)
        var input = initial
        val published = mutableListOf<SkySample>()
        backgroundScope.launch { sampleSkyBand(true, read = { input }, publish = { published.add(it) }) }
        runCurrent()
        assertEquals(listOf(initial), published)

        for (bearing in listOf(359.95, 0.125)) {
            input = initial.copy(bearing = bearing)
            advanceTimeBy(70)
            assertEquals(listOf(initial), published)
        }
        val boundary = initial.copy(bearing = 0.25)
        input = boundary
        advanceTimeBy(70)
        assertEquals(listOf(initial, boundary), published)

        val switched = boundary.copy(targets = targets.copy(intersection = skyTestSignal(4.0)), bearing = 0.3)
        input = switched
        advanceTimeBy(70)
        assertEquals(listOf(initial, boundary, switched), published)
    }

    @Test
    fun estimateQualityAndMarkStateChangesBypassTheHeadingDeadband() {
        val targets = SkyBandTargets(skyTestGuidance(27.0, null), null)
        val initial = SkySample(targets, NorthEstimate(0.0, 15.0), 0.0)
        assertTrue(!initial.copy(north = NorthEstimate(0.0, 14.9)).changedSince(initial))
        assertTrue(initial.copy(north = NorthEstimate(0.0, 15.01)).changedSince(initial))
        assertTrue(initial.copy(north = null, bearing = null).changedSince(initial))
        assertTrue(initial.copy(bearing = null).changedSince(initial))
        val beyondEdge = initial.copy(bearing = 359.9)
        assertTrue(beyondEdge.changedSince(initial))
        assertEquals(SkyMarkKind.EDGE_RIGHT, beyondEdge.frame.marks.single().kind)
    }

    @Test
    fun relativeYawUsesTheShortestClockwiseDifference() {
        assertEquals(2.0, relativeSkyYaw(1.0, 359.0), 0.0)
        assertEquals(-2.0, relativeSkyYaw(359.0, 1.0), 0.0)
        assertEquals(75.0, relativeSkyYaw(100.0, 25.0), 0.0)
    }

    @Test
    fun nextTurnAtTwentySevenDegreesRetainsTheDistanceAndOnlyTurnsHaveEdgeMarks() {
        val head = 350.0
        for ((boundary, outside, edge) in listOf(
            Triple(17.0, 17.01, SkyMarkKind.EDGE_RIGHT),
            Triple(323.0, 322.99, SkyMarkKind.EDGE_LEFT),
        )) {
            val visible = frame(head = head, targets = SkyBandTargets(skyTestGuidance(boundary, null), null))
            assertEquals(listOf(SkyMarkKind.TURN, SkyMarkKind.DISTANCE), visible.marks.map { it.kind })
            assertEquals("120m", visible.marks[1].distance)
            assertEquals(if (edge == SkyMarkKind.EDGE_RIGHT) 27f else -27f, visible.marks.first().yaw)
            val clipped = frame(head = head, targets = SkyBandTargets(
                skyTestGuidance(outside, 180.0), skyTestSignal(180.0),
            ))
            assertEquals(listOf(SkyMark(edge, if (edge == SkyMarkKind.EDGE_RIGHT) 28f else -28f)), clipped.marks)
        }
        val outside = frame(targets = SkyBandTargets(skyTestGuidance(null, 90.0), skyTestSignal(90.0)))
        assertEquals(SkyBandStatus.OUT_OF_VIEW, outside.status)
    }

    @Test
    fun destinationRequiresThreeDegreesOfSeparationAcrossNorth() {
        fun kinds(destination: Double?) = frame(
            targets = SkyBandTargets(skyTestGuidance(358.0, destination), null),
        ).marks.map { it.kind }
        assertTrue(SkyMarkKind.DESTINATION !in kinds(0.99))
        assertTrue(SkyMarkKind.DESTINATION in kinds(1.0))
        assertEquals(
            listOf(SkyMarkKind.DESTINATION),
            frame(targets = SkyBandTargets(skyTestGuidance(null, 2.0), null)).marks.map { it.kind },
        )
    }

    @Test
    fun smoothingUsesTheShortArcInBothDirectionsAndResetsAfterMissingHeading() {
        val smoother = SkyHeadingSmoother()
        assertEquals(359.0, smoother.update(359.0, 100)!!, 0.0)
        assertEquals(0.26424, smoother.update(1.0, 250)!!, 0.00001)
        assertNull(smoother.update(null, 260))
        assertEquals(1.0, smoother.update(1.0, 300)!!, 0.0)
        assertEquals(359.73576, smoother.update(359.0, 450)!!, 0.00001)
    }

    @Test
    fun suppressionHasASeparateReasonForEachGate() {
        val hidden = listOf(
            SkyBandStatus.FAST to frame(fast = true, tracking = false),
            SkyBandStatus.TRACKING_LOST to frame(tracking = false),
            SkyBandStatus.NO_ESTIMATE to frame(north = null),
            SkyBandStatus.NO_ESTIMATE to frame(head = null),
            SkyBandStatus.UNCERTAIN to frame(north = NorthEstimate(0.0, 15.01)),
            SkyBandStatus.DISABLED to frame(enabled = false),
            SkyBandStatus.NO_TARGETS to frame(targets = SkyBandTargets(null, null)),
        )
        for ((reason, result) in hidden) assertEquals(SkyBandFrame(reason), result)
        assertEquals(SkyBandStatus.SHOW, frame(north = NorthEstimate(0.0, 15.0)).status)
        val signalOnly = frame(targets = SkyBandTargets(null, skyTestSignal(4.0)))
        assertEquals(SkyMarkKind.SIGNAL, signalOnly.marks.single().kind)
        assertEquals(4f, signalOnly.marks.single().yaw)
    }

    private fun frame(
        fast: Boolean = false,
        tracking: Boolean = true,
        enabled: Boolean = true,
        north: NorthEstimate? = NorthEstimate(0.0, 5.0),
        head: Double? = 0.0,
        targets: SkyBandTargets = SkyBandTargets(skyTestGuidance(0.0, 10.0), null),
    ) = skyBandFrame(fast, tracking, enabled, north, head, targets)
}

internal fun skyTestGuidance(turn: Double?, destination: Double?): NavGuidance {
    val origin = NavPoint(0.0, 0.0)
    val point = NavPoint(0.001, 0.0)
    return NavGuidance(
        origin, 0.0, 0.0,
        turn?.let { NavTurn(NavStep(point, point, 123, listOf(point), "TURN_RIGHT", ""), 123.0, it) },
        200.0, Duration.ofMinutes(2), destination,
    )
}

internal fun skyTestSignal(bearing: Double) = PicsCandidate(
    "synthetic", MapCenter(0.001, 0.0),
    listOf(PicsLight(PicsAspect.RED, 3), PicsLight(PicsAspect.GREEN, 2)),
    PicsFreshness.FRESH, bearing, 10.0,
)
