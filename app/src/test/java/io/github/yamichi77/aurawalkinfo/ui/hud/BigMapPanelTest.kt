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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.safety.MotionState
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
    fun framedButtonsStayOutsideTheMapAndNeitherButtonClosesIt() {
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
                        false, HudDebugOptions(), {}, map.value.isOpen, bigMapMode = mode.value,
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
        for (button in listOf(destination, toggle)) {
            assertTrue(button.getUnclippedBoundsInRoot().left > mapBounds.right)
            assertFrame(view, button, expectedAlpha = 0.6f)
        }
        assertTrue(destination.getUnclippedBoundsInRoot().bottom < toggle.getUnclippedBoundsInRoot().top)
        compose.runOnIdle { state.value = state.value.copy(motionState = MotionState.WALKING) }
        assertFrame(view, toggle, expectedAlpha = 0.42f)
        toggle.performTouchInput { click(Offset(center.x, 1f)) }
        toggle.assertTextEquals("2D")
        large.assertIsDisplayed()
        destination.performTouchInput { click() }
        large.assertIsDisplayed()
        destination.assertDoesNotExist()
        large.performTouchInput { click() }
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

    private fun assertFrame(view: View, button: SemanticsNodeInteraction, expectedAlpha: Float) {
        val bounds = button.getUnclippedBoundsInRoot()
        val bitmap = compose.runOnIdle { view.drawToBitmap() }
        with(compose.density) {
            val x = ((bounds.left + bounds.right) / 2).roundToPx()
            val y = bounds.top.roundToPx() + 2.dp.roundToPx()
            assertEquals(expectedAlpha, Color(bitmap.getPixel(x, y)).blue, 0.02f)
            assertEquals(Color.Black, Color(bitmap.getPixel(x, y + 2)))
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
