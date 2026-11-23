package io.github.yamichi77.aurawalkinfo.ui.hud

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigMapPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun circularButtonsStayOutsideTheMapAndNeitherButtonClosesIt() {
        val map = mutableStateOf(BigMapControl())
        val state = mutableStateOf(hudTestState(MotionState.STOPPED).copy(mapCenter = null))
        val mode = mutableStateOf(BigMapMode.FLAT)
        val prompt = mutableStateOf<DestinationPrompt>(DestinationPrompt.Map)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            AdditiveDisplayTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PhoneScreen(
                        hudVisualSpecFor(state.value, destinationPrompt = prompt.value),
                        false, HudDebugOptions(), {}, map.value.isOpen, bigMapMode = mode.value, quantumEnabled = false,
                    ) { target ->
                        when (target) {
                            HudTarget.BIGMAP_MODE -> mode.value = BigMapMode.TILTED
                            HudTarget.NAV_DESTINATION -> prompt.value = DestinationPrompt.Listening()
                            else -> {
                                map.value = map.value.tap(target, 0, state.value.motionState)
                                if (!map.value.isOpen) prompt.value = DestinationPrompt.Map
                            }
                        }
                    }
                }
            }
        }
        val mini = compose.onNodeWithTag("map")
        val large = compose.onNodeWithTag("bigmap")
        val outside = compose.onNodeWithTag("outside")
        val destination = compose.onNodeWithTag("nav_destination")
        val toggle = compose.onNodeWithTag("bigmap_mode")
        mini.assertIsDisplayed().performClick()
        large.assertIsDisplayed()
        mini.assertDoesNotExist()
        destination.assertIsDisplayed().assertTextEquals("目的地")
        toggle.assertIsDisplayed().assertTextEquals("3D")
        val mapBounds = large.getUnclippedBoundsInRoot()
        val buttons = HudPlacement.bigMapButtons.map { placement ->
            compose.onNodeWithTag(placement.target.name.lowercase()).assertIsDisplayed()
        }
        for (button in buttons) {
            assertTrue(button.getUnclippedBoundsInRoot().left > mapBounds.right)
        }
        HudPlacement.bigMapButtons.forEach { assertRingAndIcon(view, it.target.name.lowercase(), opacity = 1f) }
        buttons.zipWithNext().forEach { (above, below) ->
            assertTrue(above.getUnclippedBoundsInRoot().bottom < below.getUnclippedBoundsInRoot().top)
        }
        compose.runOnIdle { state.value = state.value.copy(motionState = MotionState.WALKING) }
        assertRingAndIcon(view, "bigmap_mode", opacity = 0.7f)
        toggle.performTouchInput { click(Offset(center.x, 1f)) }
        toggle.assertTextEquals("2D")
        large.assertIsDisplayed()
        destination.performTouchInput { click() }
        large.assertIsDisplayed()
        destination.assertDoesNotExist()
        large.performTouchInput { click() }
        large.assertIsDisplayed()
        large.performTouchInput { click(Offset(2f, 2f)) }
        mini.assertIsDisplayed()
        large.assertDoesNotExist()
        toggle.assertDoesNotExist()
        destination.assertDoesNotExist()
        mini.performClick()
        outside.assertIsDisplayed().performTouchInput { click(Offset(5f, height - 5f)) }
        large.assertDoesNotExist()
        outside.assertDoesNotExist()
        mini.assertIsDisplayed()
    }

    @Test
    fun zoomAndRecenterButtonsAndDragControlTheViewportAcrossFixesAndReopen() {
        val fix = mutableStateOf(MapCenter(0.1, 0.2))
        val panned = MapCenter(0.11, 0.22)
        val map = mutableStateOf(BigMapControl())
        val mode = BigMapMode.FLAT
        val motion = MotionState.STOPPED
        var scroll = Offset.Zero
        var resets = 0
        val viewport = BigMapViewport().apply {
            gestureMap = object : BigMapGestureMap {
                override fun scrollBy(pixels: Offset): MapCenter {
                    scroll += pixels
                    return panned
                }
                override fun pointAt(pixel: Offset) = error("A drag must not select a destination")
            }
        }
        compose.setContent {
            AdditiveDisplayTheme {
                PhoneScreen(
                    hudVisualSpecFor(hudTestState(motion).copy(mapCenter = fix.value)),
                    false, HudDebugOptions(), {}, map.value.isOpen, quantumEnabled = false,
                    viewport = viewport, onMapPan = { resets++ },
                ) { target ->
                    val previous = map.value
                    map.value = previous.tap(target, 0, motion)
                    if (!previous.isOpen && map.value.isOpen) {
                        viewport.recenter()
                        viewport.resetZoom()
                    }
                    when (target) {
                        HudTarget.BIGMAP_ZOOM_IN -> viewport.zoomBy(mode, 1)
                        HudTarget.BIGMAP_ZOOM_OUT -> viewport.zoomBy(mode, -1)
                        HudTarget.BIGMAP_RECENTER -> viewport.recenter()
                        else -> Unit
                    }
                }
            }
        }
        fun tap(tag: String) = compose.onNodeWithTag(tag).performTouchInput { click() }
        fun assertZoom(expected: Float) = compose.runOnIdle { assertEquals(expected, viewport.zoom(mode), 0f) }
        fun assertCenter(expected: MapCenter) = compose.runOnIdle { assertEquals(expected, viewport.center(fix.value)) }
        fun dragMap() = compose.onNodeWithTag("bigmap").performTouchInput {
            swipe(center, center + Offset(60f, 40f))
        }

        tap("map")
        tap("bigmap_zoom_in")
        assertZoom(17.5f)
        repeat(2) { tap("bigmap_zoom_in") }
        assertZoom(19f)
        repeat(6) { tap("bigmap_zoom_out") }
        assertZoom(14f)
        dragMap()
        assertCenter(panned)
        compose.runOnIdle {
            assertTrue(scroll.x < 0 && scroll.y < 0)
            assertTrue(resets > 0)
            fix.value = MapCenter(0.12, 0.19)
        }
        assertCenter(panned)
        tap("bigmap_recenter")
        assertCenter(fix.value)
        dragMap()
        assertCenter(panned)
        compose.onNodeWithTag("outside").performTouchInput { click(Offset(5f, height - 5f)) }
        tap("map")
        assertCenter(fix.value)
        assertZoom(16.5f)
    }

    private fun assertRingAndIcon(view: View, name: String, opacity: Float) {
        val bounds = compose.onNodeWithTag("$name-artwork", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bitmap = compose.runOnIdle { view.drawToBitmap() }
        with(compose.density) {
            val left = bounds.left.roundToPx()
            val right = bounds.right.roundToPx()
            val x = ((bounds.left + bounds.right) / 2).roundToPx()
            val top = bounds.top.roundToPx()
            val bottom = bounds.bottom.roundToPx()
            val y = (top + bottom) / 2
            assertEquals(0.7f * opacity, Color(bitmap.getPixel(x, top)).blue, 0.13f)
            assertEquals(0.7f * opacity, Color(bitmap.getPixel(left, y)).blue, 0.13f)
            assertEquals(Color.Black, Color(bitmap.getPixel(left + 1, top + 1)))
            assertEquals(Color.Black, Color(bitmap.getPixel(right - 2, top + 1)))
            assertTrue((left + 4 until right - 4).any { iconX ->
                (top + 4 until bottom - 4).any { iconY ->
                    Color(bitmap.getPixel(iconX, iconY)).blue > 0.4f * opacity
                }
            })
        }
    }

    @Test
    fun fastMotionImmediatelyHidesAnOpenTiltedMap() {
        val map = mutableStateOf(BigMapControl().tap(HudTarget.MAP, 0, MotionState.STOPPED))
        val state = mutableStateOf(hudTestState(MotionState.STOPPED).copy(mapCenter = null))
        compose.setContent {
            AdditiveDisplayTheme {
                PhoneScreen(
                    hudVisualSpecFor(state.value), false, HudDebugOptions(), {}, map.value.isOpen,
                    bigMapMode = BigMapMode.TILTED,
                ) {}
            }
        }
        compose.onNodeWithTag("bigmap_mode").assertIsDisplayed()
        compose.onNodeWithTag("nav_destination").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            state.value = state.value.copy(motionState = MotionState.FAST)
            map.value = map.value.advance(1_000, MotionState.FAST)
            assertFalse(map.value.isOpen)
        }
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithTag("bigmap").assertDoesNotExist()
        compose.onNodeWithTag("bigmap_mode").assertDoesNotExist()
        compose.onNodeWithTag("nav_destination").assertDoesNotExist()
        compose.onNodeWithTag("quantum-bigmap").assertDoesNotExist()
    }
}
