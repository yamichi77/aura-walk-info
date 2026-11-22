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
        val left = HudPlacement.pose(Layout.leftYaw, Layout.pitch)
        for (x in listOf(-1f, 1f)) {
            for (y in listOf(-1f, 1f)) {
                val point = left.transformPoint(
                    Vector3(x * HudPlacement.statsWidthMeters / 2, y * HudPlacement.statsHeightMeters / 2, 0f),
                )
                assertTrue("yaw=${yaw(point)}", yaw(point) in -29.0..-16.0)
                val pitch = Math.toDegrees(atan2(point.y, hypot(point.x, point.z)).toDouble())
                assertTrue("pitch=$pitch", pitch in -16.0..-2.0)
            }
        }
        val right = HudPlacement.pose(Layout.rightYaw, Layout.pitch)
        val radius = HudPlacement.mapSizeMeters(Layout.stoppedDiameter) / 2
        for (angle in 0 until 360) {
            val radians = Math.toRadians(angle.toDouble())
            val point = right.transformPoint(Vector3(radius * cos(radians).toFloat(), radius * sin(radians).toFloat(), 0f))
            assertTrue("yaw=${yaw(point)}", yaw(point) in 16.0..28.0)
        }
    }

    private fun yaw(point: Vector3) = Math.toDegrees(atan2(point.x, -point.z).toDouble())
}
