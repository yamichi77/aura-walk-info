package io.github.yamichi77.aurawalkinfo.spatial

import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.nav.GuidanceCalculator
import io.github.yamichi77.aurawalkinfo.nav.NavDestination
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.NavStep
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import io.github.yamichi77.aurawalkinfo.pics.PicsPacket
import io.github.yamichi77.aurawalkinfo.pics.PicsTracker
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import java.time.Duration
import kotlin.math.atan
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialMarkModelTest {
    private val origin = NavPoint(0.0, 0.0)
    private val zero = MarkVector(0.0, 0.0, 0.0)
    private val east = MarkVector(1.0, 0.0, 0.0)
    private val north = MarkVector(0.0, 0.0, -1.0)
    private val eye = MarkVector(5.0, 2.0, 3.0)
    private val fix = SpatialFix(origin, eye, 0.0)

    @Test fun cardinalDirectionsUseTheHeadingAxesAndFixHeight() {
        val up = MarkVector.UP * 5.4
        listOf(
            geo(north = 25.0) to north,
            geo(east = 25.0) to east,
            geo(north = -25.0) to north * -1.0,
            geo(east = -25.0) to east * -1.0,
        ).forEach { (point, direction) ->
            assertVector(eye + direction * 25.0 + up, GpsSpatialProjector.project(fix, point, 7.0))
        }
        assertVector(eye + east * -25.0 + up, GpsSpatialProjector.project(fix.copy(northOffsetDegrees = 90.0), geo(north = 25.0), 7.0))
        assertVector(eye + MarkVector.UP * -1.6, GpsSpatialProjector.project(fix, origin, 0.0))
    }

    @Test fun projectionCrossesTheDateLineLocally() {
        val nearDateLine = fix.copy(point = NavPoint(0.0, 179.9999))
        val point = GpsSpatialProjector.project(nearDateLine, NavPoint(0.0, -179.9999), 1.6)
        assertEquals(22.24, point.x - eye.x, 0.01)
        assertEquals(eye.z, point.z, 1e-6)
    }

    @Test fun correctionIgnoresSubMeterChangesAndRetargetsWithoutJumping() {
        val position = SmoothedMarkPosition(zero)
        position.retarget(east * 0.99, 0)
        assertVector(zero, position.at(1_000))
        position.retarget(east, 1_000)
        assertVector(zero, position.at(1_000))
        assertVector(east * 0.5, position.at(1_500))
        position.retarget(east * 1.9, 1_500)
        assertVector(east * 0.84375, position.at(1_750))
        val before = position.at(1_750)
        position.retarget(east * 3.0, 1_750)
        assertVector(before, position.at(1_750))
        assertVector(east * 3.0, position.at(2_750))
    }

    @Test fun elevationClipsActualStrokeVerticesAndKeepsTheBoundary() {
        val view = MarkView(zero, MarkVector.UP)
        val lower = MarkVector(0.0, 6.4, -150.0)
        val upper = lower.copy(y = 38.4)
        val (first, last) = checkNotNull(view.clip(lower, upper))
        assertEquals(5.0, view.elevation(first), 1e-5)
        assertVector(upper, last)
        assertNull(view.clip(lower, lower + east))
        val boundary = MarkVector(0.0, 20 * tan(Math.toRadians(5.0)), -20.0)
        assertNotNull(view.clip(boundary, boundary + MarkVector.UP))
        val triangles = MarkStroke(lower, upper, 0.3).clippedTriangles(view, MarkVector.UP, east)
        assertTrue(triangles.isNotEmpty())
        assertTrue(triangles.all { view.elevation(it) >= 5.0 })
        assertTrue(triangles.any { view.elevation(it) in 5.0..5.01 })
        assertEquals(38.4, triangles.maxOf { it.y }, 1e-6)
    }

    @Test fun strokeThicknessAndArrowSizeMeetAngularMinimumsAcrossDistances() {
        fun angle(size: Double, distance: Double) = Math.toDegrees(2.0 * atan(size / (2.0 * distance)))
        for (distance in listOf(10.0, 40.0, 100.0)) {
            val center = eye + north * distance
            val arrow = markStrokes(MarkKind.TURN, center, north, MarkVector.UP, eye)
            val pillar = markStrokes(MarkKind.DESTINATION, center, north, MarkVector.UP, eye)
            for (stroke in arrow + pillar) {
                val far = maxOf((stroke.start - eye).length(), (stroke.end - eye).length())
                assertTrue("Stroke at ${distance}m", angle(stroke.width, far) >= 0.15 - 1e-9)
            }
            val arrowPoints = arrow.map { it.start }
            val width = arrowPoints.maxOf { it.x } - arrowPoints.minOf { it.x }
            val far = arrowPoints.maxOf { (it - eye).length() }
            assertTrue("Arrow at ${distance}m", angle(width, far) >= 3.0 - 1e-9)
        }
    }

    @Test fun segmentCanEnterAndLeaveTheVisibleConeWithBothEndsBelowFiveDegrees() {
        val view = MarkView(zero, MarkVector.UP)
        val start = MarkVector(-40.0, 1.0, -10.0)
        val end = start.copy(x = 40.0)
        val (first, last) = checkNotNull(view.clip(start, end))
        assertTrue(first.x < 0 && last.x > 0)
        assertEquals(5.0, view.elevation(first), 1e-5)
        assertEquals(5.0, view.elevation(last), 1e-5)
    }

    @Test fun safetyGatesUseInclusiveAccuracyAndUncertaintyLimits() {
        fun block(
            motion: MotionState = MotionState.WALKING,
            enabled: Boolean = true,
            tracked: Boolean = true,
            estimate: NorthEstimate? = NorthEstimate(0.0, 15.0),
            point: NavPoint? = origin,
            accuracy: Float? = 20f,
        ) = spatialBlocker(motion, enabled, tracked, estimate, point, accuracy)
        assertNull(block())
        assertEquals(MarkReason.FAST, block(motion = MotionState.FAST, enabled = false))
        assertEquals(MarkReason.DISABLED, block(enabled = false))
        assertEquals(MarkReason.TRACKING_LOST, block(tracked = false))
        assertEquals(MarkReason.NO_NORTH, block(estimate = null))
        assertEquals(MarkReason.UNCERTAIN, block(estimate = NorthEstimate(0.0, 15.01)))
        assertEquals(MarkReason.GPS_ACCURACY, block(accuracy = 20.01f))
        assertEquals(MarkReason.NO_FIX, block(motion = MotionState.NO_FIX))
        assertEquals(MarkReason.NO_FIX, block(accuracy = null))
        assertEquals(MarkReason.NO_FIX, block(point = null))
    }

    @Test fun navigationUsesGeographicDistanceAndTheRoadAfterTheTurn() {
        assertTrue(turnTooClose(15.0))
        assertFalse(turnTooClose(15.001))
        assertFalse(destinationTooFar(150.0))
        assertTrue(destinationTooFar(150.001))

        val corner = geo(north = 30.0)
        val destination = geo(north = 30.0, east = 120.0)
        val route = NavRoute(
            150, Duration.ofMinutes(2), listOf(origin, corner, destination),
            listOf(
                NavStep(origin, corner, 30, listOf(origin, corner), "DEPART", ""),
                NavStep(corner, destination, 120, listOf(corner, corner, destination), "TURN_RIGHT", ""),
            ),
        )
        val guide = GuidanceCalculator(route, destination).update(origin)
        val active = NavState.Guiding(NavDestination(destination), route, guide)
        val arrow = decideMark(MarkKind.TURN, origin, active, null, MotionState.WALKING)
        assertEquals(corner, arrow.target?.point)
        assertEquals(90.0, checkNotNull(arrow.target?.bearing), 0.01)
        assertEquals(MarkReason.TURN_TOO_CLOSE, decideMark(MarkKind.TURN, geo(north = 16.0), active, null, MotionState.WALKING).reason)
        assertEquals(MarkReason.DESTINATION_TOO_FAR, decideMark(MarkKind.DESTINATION, geo(east = -40.0), active, null, MotionState.WALKING).reason)
        assertEquals(MarkReason.SHOW, decideMark(MarkKind.DESTINATION, origin, active, null, MotionState.WALKING).reason)
        assertEquals(MarkReason.NO_GUIDANCE, decideMark(MarkKind.TURN, origin, NavState.Idle, null, MotionState.WALKING).reason)
    }

    @Test fun signalUsesOnlyAnIntersectionAcceptedByPicsTracker() {
        var now = 0L
        val tracker = PicsTracker(Clock { now })
        val trackedCenter = MapCenter(0.0, 0.0)
        val stopped = MotionState.STOPPED
        tracker.receive(PicsPacket.Signal("demo", listOf(PicsLight(PicsAspect.RED, null))), -50)
        fun update(distance: Double) {
            tracker.receive(PicsPacket.Position("demo", MapCenter(geo(north = distance).latitude, 0.0)), -50)
        }
        fun status(motion: MotionState) =
            decideMark(MarkKind.SIGNAL, origin, NavState.Idle, tracker.nearest(motion, trackedCenter), motion).reason
        update(30.01)
        assertEquals(MarkReason.NO_SIGNAL, status(stopped))
        update(30.0)
        assertEquals(MarkReason.SHOW, status(stopped))
        // The renderer checks the current motion even if the receiver's last candidate still exists.
        val candidate = tracker.nearest(stopped, trackedCenter)
        assertEquals(MarkReason.NO_SIGNAL, decideMark(MarkKind.SIGNAL, origin, NavState.Idle, candidate, MotionState.WALKING).reason)
        assertEquals(MarkReason.NO_SIGNAL, decideMark(MarkKind.SIGNAL, origin, NavState.Idle, candidate, MotionState.FAST).reason)
        now = 1_499
        assertEquals(PicsFreshness.FRESH, tracker.nearest(stopped, trackedCenter)?.freshness)
        now = 1_500
        assertEquals(PicsFreshness.STALE, tracker.nearest(stopped, trackedCenter)?.freshness)
        assertEquals(MarkReason.SHOW, status(stopped))
        now = 3_001
        assertEquals(MarkReason.NO_SIGNAL, status(stopped))
    }

    @Test fun fadeUsesThreeHundredMillisecondsAndFastCutsItImmediately() {
        val fade = MarkFade()
        assertEquals(0f, fade.update(show = true, immediate = false, now = 100))
        assertTrue(fade.isAnimating)
        assertEquals(0.25f, fade.update(show = true, immediate = false, now = 250))
        assertEquals(0.5f, fade.update(show = true, immediate = false, now = 400))
        assertFalse(fade.isAnimating)
        assertEquals(0.5f, fade.update(show = false, immediate = false, now = 400))
        assertEquals(0.25f, fade.update(show = false, immediate = false, now = 550))
        assertEquals(0f, fade.update(show = false, immediate = true, now = 551))
        assertFalse(fade.isAnimating)
        assertEquals(0f, fade.update(show = true, immediate = false, now = 552))
    }

    @Test fun noTargetDisabledAndFastDoNotScheduleUpdates() {
        val idle = spatialUpdateMode(hasTargets = false, enabled = true, motion = MotionState.STOPPED)
        val disabled = spatialUpdateMode(hasTargets = true, enabled = false, motion = MotionState.WALKING)
        val fast = spatialUpdateMode(hasTargets = true, enabled = true, motion = MotionState.FAST)
        assertNull(spatialUpdateIntervalMillis(idle, fading = false))
        assertNull(spatialUpdateIntervalMillis(disabled, fading = false))
        assertNull(spatialUpdateIntervalMillis(fast, fading = true))
    }

    @Test fun visibleMarksWaitOneHundredMillisAndOnlyFadesRunFaster() {
        val active = spatialUpdateMode(hasTargets = true, enabled = true, motion = MotionState.WALKING)
        assertTrue(checkNotNull(spatialUpdateIntervalMillis(active, fading = false)) >= 100L)
        assertEquals(33L, spatialUpdateIntervalMillis(active, fading = true))
        assertEquals(33L, spatialUpdateIntervalMillis(SpatialUpdateMode.NO_TARGETS, fading = true))
        assertEquals(33L, spatialUpdateIntervalMillis(SpatialUpdateMode.DISABLED, fading = true))
    }

    private fun geo(north: Double = 0.0, east: Double = 0.0) =
        NavPoint(Math.toDegrees(north / 6_371_000.0), Math.toDegrees(east / 6_371_000.0))

    private fun assertVector(expected: MarkVector, actual: MarkVector) {
        assertEquals(expected.x, actual.x, 1e-6)
        assertEquals(expected.y, actual.y, 1e-6)
        assertEquals(expected.z, actual.z, 1e-6)
    }
}
