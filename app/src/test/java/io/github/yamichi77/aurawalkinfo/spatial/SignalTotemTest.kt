package io.github.yamichi77.aurawalkinfo.spatial

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import io.github.yamichi77.aurawalkinfo.pics.MAX_PICS_LIGHTS
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTotemTest {
    @Test fun everyLightKeepsItsPacketOrderAndUnitlessGauge() {
        val lights = listOf(
            PicsLight(PicsAspect.RED, 1), PicsLight(PicsAspect.GREEN, 8),
            PicsLight(PicsAspect.FLASHING_GREEN, 5), PicsLight(PicsAspect.NONE, null),
            PicsLight(PicsAspect.OUT_OF_CONTROL, null), PicsLight(PicsAspect.UNKNOWN, null),
        ) + List(MAX_PICS_LIGHTS - 6) { PicsLight(PicsAspect.RED, null) }
        val totem = signalFixture(lights).toSignalTotem()
        assertEquals((1..MAX_PICS_LIGHTS).toList(), totem.rows.map { it.number })
        assertEquals(listOf("赤", "青", "青点滅", "—", "—", "—"), totem.rows.take(6).map { it.label })
        assertEquals(lights.map { it.remaining ?: 0 }, totem.rows.map { it.segments })
        assertEquals(1.2 + SIGNAL_FOOTER_HEIGHT_METERS, signalFixture(lights.take(4)).toSignalTotem().heightMeters, 1e-6)
    }

    @Test fun freshDotsHaveMatchingLumaAndStaleArtworkIsAllGray() {
        val sample = signalFixture(listOf(
            PicsLight(PicsAspect.RED, 3), PicsLight(PicsAspect.GREEN, 4),
            PicsLight(PicsAspect.FLASHING_GREEN, 1), PicsLight(PicsAspect.UNKNOWN, null),
        ))
        val fresh = sample.toSignalTotem()
        val (red, green, flashing, unknown) = fresh.rows
        assertEquals(0xFFE07A7A.toInt(), red.color)
        assertEquals(0xFF7FBFA8.toInt(), green.color)
        assertEquals(green.color, flashing.color)
        assertEquals(0xFF888780.toInt(), unknown.color)
        assertEquals(0xFFE0F7FF.toInt(), fresh.textColor)
        val redLuma = 0.2126f * 224 + 0.7152f * 122 + 0.0722f * 122
        val greenLuma = 0.2126f * 127 + 0.7152f * 191 + 0.0722f * 168
        assertEquals(redLuma * red.outlineOpacity, greenLuma * green.outlineOpacity, 0.001f)
        assertTrue(fresh.rows.all { it.fillOpacity <= 0.3f })
        assertEquals(0f, flashing.fillOpacity)
        val stale = sample.copy(freshness = PicsFreshness.STALE).toSignalTotem()
        assertEquals(0xFF888780.toInt(), stale.textColor)
        assertTrue(stale.rows.all { it.color == stale.textColor })
        assertEquals(fresh.rows.map { it.label }, stale.rows.map { it.label })
    }

    @Test fun placementLiftsOnlyVerticallyAndKeepsThePanelUpright() {
        val source = MarkVector(0.0, 4.0, -15.0)
        val up = MarkVector.UP
        val eye = MarkVector(2.0, 3.5, 0.0)
        val height = 1.36
        val placed = placeSignalTotem(source, eye, up, height)
        val bottom = placed.center - up * (height / 2)
        assertEquals(eye.y, bottom.y, 1e-9)
        assertEquals(source.x, bottom.x, 0.0)
        assertEquals(source.z, bottom.z, 0.0)
        assertEquals(0.0, MarkView(eye, up).elevation(bottom), 1e-9)

        val pose = Pose(rotation = signalPanelRotation(placed.towardEye, up))
        val front = pose.transformVector(Vector3(0f, 0f, 1f))
        val facing = placed.towardEye * (1.0 / placed.towardEye.length())
        assertEquals(facing.x, front.x.toDouble(), 1e-6)
        assertEquals(facing.z, front.z.toDouble(), 1e-6)
        assertEquals(0f, front.y, 1e-6f)
        assertEquals(Vector3(0f, 1f, 0f), pose.transformVector(Vector3(0f, 1f, 0f)))
        assertEquals(source, placeSignalTotem(source, eye.copy(y = 1.6), up, height).center)
    }

    @Test fun signalOpacityImmediatelyClearsAnIneligibleTotem() {
        val fade = MarkFade()
        for (reason in listOf(
            MarkReason.NO_SIGNAL, MarkReason.FAST, MarkReason.TRACKING_LOST, MarkReason.NO_NORTH,
            MarkReason.UNCERTAIN, MarkReason.GPS_ACCURACY, MarkReason.DISABLED,
        )) {
            assertEquals(0f, fade.signalOpacity(MarkReason.SHOW, 100L))
            assertEquals(MARK_MAX_OPACITY, fade.signalOpacity(MarkReason.SHOW, 400L))
            assertEquals(0f, fade.signalOpacity(reason, 401L))
            assertTrue(!fade.isAnimating)
        }
    }
}

internal fun signalFixture(lights: List<PicsLight>) =
    PicsCandidate("test", MapCenter(0.00009, 0.0), lights, PicsFreshness.FRESH, 0.0, 10.0)
