package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.xr.runtime.math.Vector3
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class HudPlacementTest {
    @Test
    fun everySkyMarkPanelFitsTheUpperBandAtBothEdgesAndInTheCenter() {
        for (kind in SkyMarkKind.entries) {
            val layout = skyMarkLayout(kind)
            for (yaw in listOf(-28f, 0f, 28f)) {
                val pose = HudPlacement.pose(yaw, layout.pitch)
                val bounds = skyPitchBounds(pose, layout.widthMeters, layout.heightMeters)
                assertTrue("$kind at $yaw: $bounds", bounds.insideBand)
                assertEquals(if (kind == SkyMarkKind.DISTANCE) 15f else 17.5f, layout.pitch, 0f)
            }
        }
    }

    @Test
    fun bigMapArrowMatchesWalkingMiniMapAngularHeight() {
        val miniMap = HudPlacement.pose(Layout.rightYaw, Layout.pitch)
        val bigMap = HudPlacement.pose(0f, HudPlacement.bigMapPitch)
        val miniArrow = mapArrowHeight(HudPlacement.mapSizeMeters(Layout.walkingDiameter))
        val miniAngle = 2 * atan2(miniArrow / 2, miniMap.translation.length)
        val bigAngle = 2 * atan2(HudPlacement.bigMapArrowHeightMeters / 2, bigMap.translation.length)
        assertEquals(miniAngle, bigAngle, 0.0001f)
        assertTrue(HudPlacement.bigMapArrowHeightMeters < mapArrowHeight(HudPlacement.bigMapSizeMeters))
    }

    @Test
    fun leftPanelIsOnSphereAndFacesHead() {
        val pose = HudPlacement.pose(-22f, -8f)
        assertEquals(-0.55644f, pose.translation.x, 0.0001f)
        assertEquals(-0.20876f, pose.translation.y, 0.0001f)
        assertEquals(-1.37724f, pose.translation.z, 0.0001f)
        assertEquals(1.5f, pose.translation.length, 0.0001f)
        val towardHead = -pose.translation / pose.translation.length
        assertTrue(Vector3.distance(towardHead, pose.backward) < 0.0001f)
    }

    @Test
    fun rectangleFitsApprovedBoundsAndCircularEdgeKeepsClearGap() {
        val left = HudPlacement.statsPose()
        for (x in listOf(-1f, 1f)) {
            for (y in listOf(-1f, 1f)) {
                val point = left.transformPoint(
                    Vector3(x * HudPlacement.statsWidthMeters / 2, y * HudPlacement.statsHeightMeters / 2, 0f),
                )
                assertTrue("yaw=${yaw(point)}", yaw(point) >= -29.0 && yaw(point) < -16.0)
                val pitch = Math.toDegrees(atan2(point.y, hypot(point.x, point.z)).toDouble())
                assertTrue("pitch=$pitch", pitch >= -16.0 && pitch < 0.0)
            }
        }
        assertTrue(left.transformPoint(Vector3(0f, HudPlacement.statsHeightMeters / 2, 0f)).y < 0f)
        val right = HudPlacement.pose(Layout.rightYaw, Layout.pitch)
        val radius = HudPlacement.mapSizeMeters(Layout.stoppedDiameter) / 2
        for (angle in 0 until 360) {
            val radians = Math.toRadians(angle.toDouble())
            val point = right.transformPoint(Vector3(radius * cos(radians).toFloat(), radius * sin(radians).toFloat(), 0f))
            assertTrue("yaw=${yaw(point)}", yaw(point) in 16.0..28.0)
        }
    }

    @Test
    fun navigationStaysAboveTheMapAndOutsideSixteenDegreesDuringResize() {
        for (diameter in 8..12) {
            val size = HudPlacement.mapSizeMeters(diameter.toFloat())
            val nav = HudPlacement.navPose(size)
            val map = HudPlacement.pose(Layout.rightYaw, Layout.pitch)
            val bottom = nav.transformPoint(Vector3(0f, -HudPlacement.navHeightMeters / 2, 0f))
            val top = map.transformPoint(Vector3(0f, size / 2, 0f))
            assertTrue(bottom.y > top.y)
            assertEquals(Layout.rightYaw.toDouble(), yaw(nav.translation), 0.0001)
            for (y in listOf(-HudPlacement.navHeightMeters / 2, HudPlacement.navHeightMeters / 2)) {
                val inside = nav.transformPoint(Vector3(-HudPlacement.navWidthMeters / 2, y, 0f))
                assertTrue("diameter=$diameter yaw=${yaw(inside)}", yaw(inside) > 16.0)
            }
        }
    }

    private fun yaw(point: Vector3) = Math.toDegrees(atan2(point.x, -point.z).toDouble())

    @Test
    fun navigationWithArrowStaysWithinPeripheryThroughoutResize() {
        val width = HudPlacement.navWidthMeters(withArrow = true)
        for (tenth in 80..120) {
            val size = HudPlacement.mapSizeMeters(tenth / 10f)
            val panel = HudPlacement.navPose(size, withArrow = true)
            val map = HudPlacement.pose(Layout.rightYaw, Layout.pitch)
            for (y in listOf(-HudPlacement.navHeightMeters / 2, HudPlacement.navHeightMeters / 2)) {
                val inner = panel.transformPoint(Vector3(-width / 2, y, 0f))
                val outer = panel.transformPoint(Vector3(width / 2, y, 0f))
                assertTrue("diameter=$tenth inner=${yaw(inner)}", yaw(inner) > 16.0)
                assertTrue("diameter=$tenth outer=${yaw(outer)}", yaw(outer) < 30.0)
            }
            assertTrue(panel.transformPoint(Vector3(0f, -HudPlacement.navHeightMeters / 2, 0f)).y >
                map.transformPoint(Vector3(0f, size / 2, 0f)).y)
        }
    }
}
