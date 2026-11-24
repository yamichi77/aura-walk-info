package io.github.yamichi77.aurawalkinfo.spatial

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import io.github.yamichi77.aurawalkinfo.diagnostics.markPreviewNav
import io.github.yamichi77.aurawalkinfo.heading.HeadPose
import io.github.yamichi77.aurawalkinfo.heading.HeadTracking
import io.github.yamichi77.aurawalkinfo.heading.HeadingFix
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.nav.GuidanceCalculator
import io.github.yamichi77.aurawalkinfo.nav.NavDestination
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.NavStep
import io.github.yamichi77.aurawalkinfo.nav.bearingTo
import io.github.yamichi77.aurawalkinfo.nav.distanceTo
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
import kotlin.math.cos
import kotlin.math.sin
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
        val up = MarkVector.UP * 0.6
        val height = MarkKind.TURN.baseHeight
        assertEquals(2.2, height, 1e-9)
        listOf(
            geo(north = 25.0) to north,
            geo(east = 25.0) to east,
            geo(north = -25.0) to north * -1.0,
            geo(east = -25.0) to east * -1.0,
        ).forEach { (point, direction) ->
            assertVector(eye + direction * 25.0 + up, GpsSpatialProjector.project(fix, point, height))
        }
        assertVector(eye + east * -25.0 + up, GpsSpatialProjector.project(fix.copy(northOffsetDegrees = 90.0), geo(north = 25.0), height))
        assertVector(eye + MarkVector.UP * -1.6, GpsSpatialProjector.project(fix, origin, 0.0))
    }

    @Test fun gpsUpdateMovesTheMarkWithTheSameTrackedEyePosition() {
        val target = geo(north = 40.0)
        val tracking = HeadingTracking()
        val stationaryEye = Vector3(5f, 2f, 3f)
        fun project(point: NavPoint, fixTime: Long): MarkVector {
            // The GPS callback arrives 600 ms after the fix, with the same tracked eye position.
            for (time in fixTime..fixTime + 600 step 100) {
                tracking.onPose(HeadPose(time, HeadTracking.TRACKING, perceptionPosition = stationaryEye))
            }
            tracking.onFix(HeadingFix(fixTime, 0.0, 8.0, 1.4), walking = true)
            val referenceEye = checkNotNull(tracking.poseForFix(fixTime)?.perceptionPosition)
            val trackedEye = MarkVector(referenceEye.x.toDouble(), referenceEye.y.toDouble(), referenceEye.z.toDouble())
            return GpsSpatialProjector.project(SpatialFix(point, trackedEye, 0.0), target, 7.0)
        }
        val initial = project(origin, 0)
        val updated = project(geo(north = 10.0), 900)
        val position = SmoothedMarkPosition(initial)
        position.retarget(updated, 500)
        assertVector(initial, position.at(500))
        assertVector(initial - north * 10.0, position.at(1_500))
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

    @Test fun arrowBendsFromTheViewersBearingToTheCorner() {
        val up = MarkVector.UP
        val cases = mapOf(
            0.0 to 0.0, 19.9 to 0.0, -19.9 to 0.0, 20.1 to 20.1,
            45.0 to 45.0, 90.0 to 90.0, -90.0 to -90.0, 149.9 to 149.9,
            150.1 to 180.0, -150.1 to -180.0,
        )
        val viewerBearing = 75.0
        for ((relative, expected) in cases) {
            assertEquals(expected, turnBendDegrees(direction(viewerBearing), direction(viewerBearing + relative), up), 1e-9)
        }

        val view = MarkView(zero, up)
        fun arrow(relative: Double) =
            standingArrow(north * 20.0, view, north * -1.0, direction(relative), false).artwork as MarkArtwork.Arrow
        val right = arrow(90.0)
        val left = arrow(-90.0)
        assertTrue(right.shaft.last().x > right.shaft.first().x)
        assertTrue(left.shaft.last().x < left.shaft.first().x)
        assertEquals(right.shaft[1].y, right.shaft.last().y, 1e-9)
        assertTrue(arrow(45.0).shaft.last().y < arrow(45.0).shaft[1].y)
        assertEquals(2, arrow(0.0).shaft.size)
        val uturn = arrow(175.0)
        assertTrue(uturn.shaft.last().y > uturn.shaft[2].y)
        assertTrue(uturn.head[1].y > uturn.head[0].y && uturn.head[1].y > uturn.head[2].y)
    }

    @Test fun panelFacesTheEyeWithNoPitchOrRollEvenInARotatedAnchor() {
        val up = MarkVector(0.0, 0.8, 0.6)
        val base = MarkVector(1.0, 0.0, 3.0)
        val offset = east * 4.0 + MarkVector(0.0, -0.6, 0.8) * 3.0
        val view = MarkView(base + offset + up * 1.5, up)
        val facing = markPanelFacing(base, view, north)
        assertVector(offset, facing)
        val rotation = Pose(rotation = signalPanelRotation(facing, up))
        val front = rotation.transformVector(Vector3(0f, 0f, 1f))
        val vertical = rotation.transformVector(Vector3(0f, 1f, 0f))
        assertVector(offset * 0.2, MarkVector(front.x.toDouble(), front.y.toDouble(), front.z.toDouble()))
        assertVector(up, MarkVector(vertical.x.toDouble(), vertical.y.toDouble(), vertical.z.toDouble()))
        assertVector(facing, markPanelFacing(base, MarkView(base + up, up), facing))
    }

    @Test fun arrowKeepsItsAngularWidthStrokeAndClearanceAtNearAndFarDistances() {
        assertFalse(isNearTurn(10.001))
        assertEquals(1f, arrowOpacity(false), 0f)
        val up = MarkVector.UP
        val view = MarkView(eye + up * 0.8, up)
        for (distance in listOf(0.0, 0.1, 1.0, 5.0, 10.0, 20.0, 150.0)) {
            val near = isNearTurn(distance)
            assertEquals(distance <= 10.0, near)
            if (near) assertEquals(0.5f, arrowOpacity(near), 0f)
            val base = eye + north * distance + up * 0.6
            for (relative in -180..180 step 5) {
                val mark = standingArrow(base, view, north * -1.0, direction(relative.toDouble()), near)
                val arrow = mark.artwork as MarkArtwork.Arrow
                assertEquals(0.6, (mark.bottom - view.eye).dot(up), 1e-9)
                assertEquals(0.0, (mark.bottom - base).dot(north), 1e-9)
                val topDistance = (mark.bottom + up * arrow.height - view.eye).length()
                val inkWidth = arrow.width - (2 * STROKE_PADDING - 1) * arrow.stroke
                assertTrue(angle(inkWidth, topDistance) >= 5.0 - 1e-9)
                assertTrue(angle(arrow.stroke, topDistance) >= 0.4 - 1e-9)
                if (near) assertTrue(angle(arrow.width, (mark.bottom - view.eye).length()) <= 8.0 + 1e-9)
            }
        }
    }

    @Test fun destinationHeadIsFifteenDegreesUpAtFiveTwentyAndOneHundredMeters() {
        val up = MarkVector.UP
        val view = MarkView(zero, up)
        for ((distance, height) in listOf(5.0 to 2.94, 20.0 to 6.96, 100.0 to 28.39)) {
            val ground = north * distance - up * GpsSpatialProjector.EYE_HEIGHT_METERS
            val mark = checkNotNull(standingDestination(destinationHeadTarget(ground, view), ground, view, north * -1.0))
            val art = mark.artwork as MarkArtwork.Destination
            val top = mark.bottom + up * art.height
            val head = top - up * art.headY
            val tip = top - up * art.tipY
            assertEquals(DESTINATION_HEAD_ELEVATION_DEGREES, view.elevation(head), 1e-9)
            assertEquals(height, (head - ground).dot(up), 0.01)
            assertTrue(tip.y >= 0.6)
            assertTrue(angle(2 * PIN_HEAD_RADIUS * art.pinScale, head.length()) >= 2.5 - 1e-9)
            assertTrue(angle(art.stroke, tip.length()) >= 0.4 - 1e-9)
            val pinHeight = art.tipY - art.stroke * STROKE_PADDING
            assertEquals(pinHeight / 2, art.tailEndY - art.tipY, 1e-9)
        }
    }

    @Test fun destinationRespectsTipAndGroundHeightLimitsAndClipsTheNearTail() {
        val up = MarkVector.UP
        val view = MarkView(zero, up)
        val ground = north * 5.0 - up * GpsSpatialProjector.EYE_HEIGHT_METERS
        val laggingHead = north * 5.0 + up * 0.77
        val mark = checkNotNull(standingDestination(laggingHead, ground, view, north * -1.0))
        val art = mark.artwork as MarkArtwork.Destination
        assertEquals(0.6, (mark.bottom + up * (art.height - art.tipY)).y, 1e-9)
        assertTrue(art.visibleHeight > art.tipY && art.visibleHeight < art.tailEndY)
        val clipEdge = mark.bottom + up * (art.height - art.visibleHeight) + east * (art.width / 2)
        assertTrue(view.elevation(clipEdge) in 5.0..5.02)
        assertTrue(view.elevation(clipEdge - up * 0.002) < 5.0)

        val farGround = north * 150.0 - up * GpsSpatialProjector.EYE_HEIGHT_METERS
        val highView = MarkView(up * 15.0, up)
        val capped = checkNotNull(
            standingDestination(destinationHeadTarget(farGround, highView), farGround, highView, north * -1.0),
        )
        assertEquals(40.0, (capped.bottom - farGround).dot(up) + capped.artwork.height, 1e-9)
        assertNull(standingDestination(laggingHead, ground - up * 50.0, view, north * -1.0))
    }

    @Test fun destinationHeadHeightSmoothsASubMeterChangeInWalkingDistance() {
        val up = MarkVector.UP
        val ground = north * 20.0 - up * GpsSpatialProjector.EYE_HEIGHT_METERS
        val initial = destinationHeadTarget(ground, MarkView(zero, up))
        val nextView = MarkView(north * 0.5, up)
        val next = destinationHeadTarget(ground, nextView)
        val position = SmoothedMarkPosition(initial, minimumChangeMeters = 0.05)
        position.retarget(next, 500)
        assertVector(initial, position.at(500))
        assertVector(initial.toward(next, 0.5), position.at(1_000))
        assertVector(next, position.at(1_500))
    }

    @Test fun previewSelectsTheOutgoingDirectionAndDestination() {
        val cases = mapOf(
            null to 90.0, "unknown" to 90.0, "right" to 90.0, "left" to 270.0,
            "straight" to 0.0, "uturn" to 180.0, "slight_right" to 45.0,
        )
        for ((key, bearing) in cases) {
            val nav = markPreviewNav(origin, 20.0, key)
            val turn = checkNotNull(nav.guidance.nextTurn)
            val corner = turn.step.start
            assertEquals(0.0, checkNotNull(origin.bearingTo(corner)), 1e-9)
            assertEquals(20.0, origin.distanceTo(corner), 0.03)
            assertEquals(bearing, checkNotNull(corner.bearingTo(nav.destination.point)), 1e-6)
            assertEquals(15.0, corner.distanceTo(nav.destination.point), 0.02)
            assertEquals(nav.destination.point, turn.step.end)
        }
    }

    private fun direction(bearing: Double): MarkVector {
        val angle = Math.toRadians(bearing)
        return MarkVector(sin(angle), 0.0, -cos(angle))
    }

    private fun angle(width: Double, distance: Double) = Math.toDegrees(2 * atan(width / (2 * distance)))

    @Test fun standingPanelCleanupOnlyDisablesAndDetachesWhileTheSessionIsLive() {
        val calls = mutableListOf<String>()
        val disable = { calls += "disable" }
        val detachFromScene = { calls += "detach" }
        hideSpatialEntity(detach = false, disable, detachFromScene)
        assertEquals(listOf("disable"), calls)
        calls.clear()
        hideSpatialEntity(detach = true, disable, detachFromScene)
        assertEquals(listOf("disable", "detach"), calls)
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
        val calculator = GuidanceCalculator(route, destination)
        val guide = calculator.update(origin)
        val active = NavState.Guiding(NavDestination(destination), route, guide)
        val arrow = decideMark(MarkKind.TURN, origin, active, null, MotionState.WALKING)
        assertEquals(corner, arrow.target?.point)
        assertEquals(90.0, checkNotNull(arrow.target?.bearing), 0.01)
        val justBefore = geo(north = 29.0)
        val approaching = active.copy(guidance = calculator.update(justBefore))
        assertEquals(arrow, decideMark(MarkKind.TURN, justBefore, approaching, null, MotionState.WALKING))
        val justAfter = geo(north = 30.0, east = 1.0)
        val passed = active.copy(guidance = calculator.update(justAfter))
        assertEquals(MarkReason.NO_TURN, decideMark(MarkKind.TURN, justAfter, passed, null, MotionState.WALKING).reason)
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
