package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Intent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.settings.HudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyBandPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun guidanceAndIntersectionRenderOnlyLocalUnfilledMarksAndDistance() {
        val frame = skyBandFrame(
            false, true, true, NorthEstimate(0.0, 5.0), 0.0,
            SkyBandTargets(skyTestGuidance(0.0, 5.0), skyTestSignal(10.0)),
        )
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Row(Modifier.background(Color.Black)) {
                frame.marks.forEach { SkyMarkContent(it, 0.5f, 20f, Modifier.size(64.dp)) }
            }
        }
        for (kind in listOf("TURN", "DISTANCE", "DESTINATION", "SIGNAL")) {
            compose.onNodeWithTag("sky-$kind").assertIsDisplayed()
        }
        compose.onNodeWithText("120m").assertIsDisplayed()
        val pixels = compose.runOnIdle { view.drawToBitmap().asImageBitmap().toPixelMap() }
        for (left in listOf(0, 128, 192)) {
            assertEquals(Color.Black, pixels[left + 32, 32])
            assertTrue((left + 20..left + 44).any { x -> (20..44).any { y -> pixels[x, y] != Color.Black } })
            for (y in 0 until 64) {
                for (x in listOf(left + 2, left + 15, left + 48, left + 61)) {
                    assertEquals("Unexpected stroke outside the marker at $x,$y", Color.Black, pixels[x, y])
                }
            }
        }
        val red = pixels[221, 46]
        val green = pixels[227, 46]
        assertNotEquals(Color.Black, red)
        assertNotEquals(Color.Black, green)
        assertTrue(red.red > red.green && green.green > green.red)
        for (x in 0 until 256) {
            for (y in 0 until 64) {
                val pixel = pixels[x, y]
                assertTrue("Opacity exceeds 50% at $x,$y: $pixel", maxOf(pixel.red, pixel.green, pixel.blue) <= 0.51f)
            }
        }
    }

    @Test
    fun allHideGatesRemoveMarksAndFastSkipsTheFade() {
        compose.mainClock.autoAdvance = false
        val shown = SkyBandFrame(SkyBandStatus.SHOW, listOf(SkyMark(SkyMarkKind.TURN, 0f)))
        val frame = mutableStateOf(shown)
        compose.setContent {
            Box {
                val band = rememberSkyBandVisibility(frame.value)
                band?.marks?.forEach { SkyMarkContent(it, band.alpha, 20f, Modifier.size(60.dp)) }
            }
        }
        val mark = compose.onNodeWithTag("sky-TURN")
        for (status in listOf(
            SkyBandStatus.TRACKING_LOST, SkyBandStatus.NO_ESTIMATE, SkyBandStatus.UNCERTAIN,
            SkyBandStatus.DISABLED, SkyBandStatus.NO_TARGETS, SkyBandStatus.FAST,
        )) {
            compose.mainClock.advanceTimeBy(350)
            mark.assertIsDisplayed()
            changeFrame { frame.value = SkyBandFrame(status) }
            compose.mainClock.advanceTimeByFrame()
            if (status == SkyBandStatus.FAST) {
                mark.assertDoesNotExist()
            } else {
                compose.mainClock.advanceTimeBy(140)
                mark.assertIsDisplayed()
                compose.mainClock.advanceTimeBy(210)
                mark.assertDoesNotExist()
            }
            changeFrame { frame.value = shown }
        }
    }

    private fun changeFrame(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
    }

    @Test
    fun debugTogglePersistsAndEstimateOverrideIsTemporary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = HudSettings(context)
        val original = storage.skyBand
        try {
            val settings = SkyBandSettings(storage)
            assertTrue(settings.enabled)
            settings.applyDebugIntent(Intent().putExtra("sky_band", false)
                .putExtra("north_offset", 30f).putExtra("north_uncertainty", 5f))
            assertEquals(NorthEstimate(30.0, 5.0), settings.northOverride)
            assertEquals(false, SkyBandSettings(HudSettings(context)).enabled)
            assertNull(SkyBandSettings(HudSettings(context)).northOverride)
            settings.applyDebugIntent(Intent().putExtra("clear_north", true))
            assertNull(settings.northOverride)
        } finally {
            storage.skyBand = original
        }
    }
}
