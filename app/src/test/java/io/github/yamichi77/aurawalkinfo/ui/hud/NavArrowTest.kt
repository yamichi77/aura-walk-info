package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.heading.HeadPose
import io.github.yamichi77.aurawalkinfo.heading.HeadTracking
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.heading.hudHeadBearing
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavArrowTest {
    @Test
    fun projectsBothOutlinesAndVerticalEdgesInTheExpectedDirections() {
        val forward = NavArrowStyle.project(0.0)
        val right = NavArrowStyle.project(90.0)
        val left = NavArrowStyle.project(-90.0)
        assertEquals(21, forward.size)
        assertEquals(0f, forward.first().from.x, 0.0001f)
        assertTrue(forward.first().from.y < forward.first().to.y)
        assertTrue(right.first().from.x > right.first().to.x)
        assertTrue(left.first().from.x < left.first().to.x)
        assertEquals(forward[0].from, forward[2].from)
        assertEquals(forward[1].from, forward[2].to)
        assertNotEquals(forward[2].from, forward[2].to)
        val flatter = NavArrowStyle.project(0.0, tilt = 70.0)
        assertTrue((flatter[0].to.y - flatter[0].from.y) < (forward[0].to.y - forward[0].from.y))
    }

    @Test
    fun choosesQualifiedTrackedHeadBeforeGpsAndDoesNotUseGpsWithoutFix() {
        val input = NavArrowInput(travelBearing = 90.0, gpsCourseDeg = 30f, noFix = false)
        val head = HeadPose(0, HeadTracking.TRACKING, yawDegrees = -25.0)
        val north = NorthEstimate(5.0, 15.0)
        val headAim = NavArrowAim(NavArrowReference.HEAD, 60.0)
        val gpsAim = NavArrowAim(NavArrowReference.GPS, 60.0)
        assertEquals(headAim, input.aim(hudHeadBearing(head, north)))
        assertEquals(gpsAim, input.aim(hudHeadBearing(head, north.copy(uncertaintyDegrees = 15.01))))
        assertEquals(gpsAim, input.aim(hudHeadBearing(head.copy(tracking = HeadTracking.PAUSED), north)))
        assertEquals(gpsAim, input.aim(hudHeadBearing(head, null)))
        assertNull(input.copy(gpsCourseDeg = null).aim(null))
        assertNull(input.copy(noFix = true).aim(null))
        assertEquals(headAim, input.copy(noFix = true).aim(hudHeadBearing(head, north)))
    }

    @Test
    fun switchesAtFifteenMetersToTheFirstOutgoingSegment() {
        val guidance = sampleGuiding().guidance
        val turn = checkNotNull(guidance.nextTurn)
        val corner = NavPoint(0.0, 0.0)
        val waypoints = listOf(corner, corner, NavPoint(0.0, 0.0001), NavPoint(0.0001, 0.0001))
        val nearby = turn.copy(
            step = turn.step.copy(start = corner, end = waypoints.last(), polyline = waypoints),
            distanceMeters = 15.0,
            bearingDegrees = 12.0,
        )
        assertEquals(12.0, navArrowTravelBearing(guidance.copy(nextTurn = nearby.copy(distanceMeters = 15.01))))
        assertEquals(90.0, navArrowTravelBearing(guidance.copy(nextTurn = nearby)))
        assertEquals(90.0, navArrowTravelBearing(guidance.copy(nextTurn = nearby.copy(distanceMeters = 1.0))))
        assertEquals(42.0, navArrowTravelBearing(guidance.copy(nextTurn = null, destinationBearingDegrees = 42.0)))
    }

    @Test
    fun takesTheShortWayAndFiltersSmallOrTooFrequentUpdates() {
        assertEquals(2.0, shortestArrowTurn(359.0, 1.0), 0.0)
        assertEquals(-2.0, shortestArrowTurn(1.0, 359.0), 0.0)
        val updates = NavArrowUpdates()
        fun propose(now: Long, angle: Double) = updates.update(now, NavArrowAim(NavArrowReference.HEAD, angle))?.angle
        assertEquals(359.0, propose(0, 359.0))
        assertEquals(359.0, propose(99, 1.0))
        assertEquals(359.0, propose(100, 0.99))
        assertEquals(1.0, propose(101, 1.0))
        assertNull(updates.update(102, null))
        assertNull(propose(200, 30.0))
        assertEquals(30.0, propose(201, 30.0))
    }
}
