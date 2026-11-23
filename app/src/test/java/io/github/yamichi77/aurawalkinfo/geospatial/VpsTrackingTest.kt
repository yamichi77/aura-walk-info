package io.github.yamichi77.aurawalkinfo.geospatial

import androidx.xr.runtime.math.Vector3
import io.github.yamichi77.aurawalkinfo.heading.HeadPose
import io.github.yamichi77.aurawalkinfo.heading.HeadTracking
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.heading.hudHeadBearing
import io.github.yamichi77.aurawalkinfo.heading.preferredNorth
import io.github.yamichi77.aurawalkinfo.nav.NavDestination
import io.github.yamichi77.aurawalkinfo.nav.NavGuidance
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.spatial.MarkVector
import io.github.yamichi77.aurawalkinfo.spatial.SmoothedMarkPosition
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsTrackingTest {
    @Test
    fun requiresConsentCoverageTrackingAndBothAccuracyThresholds() {
        assertEquals(VpsStatus.READY, status(horizontal = 5.0, yaw = 5.0))
        assertEquals(VpsStatus.LOW_ACCURACY, status(horizontal = 5.01))
        assertEquals(VpsStatus.LOW_ACCURACY, status(yaw = 5.01))
        assertEquals(VpsStatus.LOW_ACCURACY, status(yaw = Double.NaN))
        assertEquals(VpsStatus.NOT_RUNNING, status(running = false))
        assertEquals(VpsStatus.TRACKING_LOST, status(tracking = false))
        assertEquals(VpsStatus.NO_CONSENT, status(consent = null))
        assertEquals(VpsStatus.NO_CONSENT, status(consent = false))
        assertEquals(VpsStatus.COVERAGE_PENDING, status(available = null))
        assertEquals(VpsStatus.COVERAGE_UNAVAILABLE, status(available = false))
        assertEquals(VpsStatus.IDLE, vpsStatus(true, false, true, true, true, 1.0, 1.0))
    }

    @Test
    fun trackerIsNeededOnlyForGuidanceOrSignal() {
        val point = NavPoint(0.1, 0.1)
        val duration = Duration.ofMinutes(1)
        val route = NavRoute(100, duration, listOf(point, NavPoint(0.101, 0.1)), emptyList())
        val guidance = NavGuidance(point, 0.0, 0.0, null, 100.0, duration, null)
        val guiding = NavState.Guiding(NavDestination(point), route, guidance)
        assertTrue(vpsDemand(guiding, false))
        assertTrue(vpsDemand(NavState.Rerouting(guiding), false))
        assertTrue(vpsDemand(NavState.Idle, true))
        assertFalse(vpsDemand(NavState.Idle, false))
    }

    @Test
    fun checksCoverageForTheFirstFixAndAfterMovingFiveHundredMeters() {
        val origin = NavPoint(0.0, 0.0)
        assertTrue(needsVpsCoverageCheck(null, origin))
        assertFalse(needsVpsCoverageCheck(origin, NavPoint(0.00449, 0.0)))
        assertTrue(needsVpsCoverageCheck(origin, NavPoint(0.00450, 0.0)))
    }

    @Test
    fun northUsesEastUpSouthAndFallsBackToGpsWhenVpsLosesTracking() {
        val pose = HeadPose(0, HeadTracking.TRACKING, yawDegrees = -90.0)
        val gps = NorthEstimate(18.0, 12.0)
        val vps = vpsNorth(Vector3(1f, 0f, 0f), Vector3(0f, 0f, -1f), 3.0)
        assertEquals(NorthEstimate(270.0, 3.0), preferredNorth(vps) { gps })
        assertEquals(0.0, hudHeadBearing(pose, preferredNorth(vps) { gps })!!, 1e-6)
        val lost = vps.takeIf { status(tracking = false) == VpsStatus.READY }
        assertEquals(gps, preferredNorth(lost) { gps })
        assertEquals(108.0, hudHeadBearing(pose, preferredNorth(lost) { gps })!!, 1e-6)
    }

    @Test
    fun bothSourceChangesContinueFromTheCurrentInterpolatedPosition() {
        val gps = MarkVector(0.0, 0.0, 0.0)
        val vps = MarkVector(0.8, 0.0, 0.0)
        val position = SmoothedMarkPosition(gps)
        position.retarget(vps, 1_000L, sourceChanged = true)
        assertEquals(gps, position.at(1_000L))
        val mid = position.at(1_500L)
        assertEquals(0.4, mid.x, 1e-6)
        position.retarget(gps, 1_500L, sourceChanged = true)
        assertEquals(mid, position.at(1_500L))
        assertEquals(gps, position.at(2_500L))
    }

    private fun status(
        consent: Boolean? = true, available: Boolean? = true, running: Boolean = true, tracking: Boolean = true,
        horizontal: Double = 1.0, yaw: Double = 1.0,
    ) = vpsStatus(consent, true, available, running, tracking, horizontal, yaw)
}
