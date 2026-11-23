package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.ui.theme.HudArrow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniMapOrientationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cameraBearingIsZeroAtBothMapZooms() {
        assertEquals(0f, northUpCameraPosition(MapCenter(0.1, 0.2), 17.5f).bearing, 0f)
        assertEquals(0f, northUpCameraPosition(MapCenter(0.2, 0.3), 16.5f).bearing, 0f)
    }

    @Test
    fun rotationTakesTheShortPathAcrossNorth() {
        assertEquals(361f, shortestArrowTarget(359f, 1f), 0f)
        assertEquals(-1f, shortestArrowTarget(1f, 359f), 0f)
    }

    @Test
    fun markerDrawsACircleWithoutHeadingAndRotatesClockwiseWhenHeadingChanges() {
        val state = mutableStateOf(hudTestState(MotionState.STOPPED))
        compose.setContent {
            MiniMapPlaceholder(
                hudVisualSpecFor(state.value) as HudVisualSpec.Clusters,
                Modifier.size(100.dp).background(Color.Black).testTag("map"),
            )
        }

        fun assertMarkerPixels(top: Color, right: Color) {
            val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                val window = compose.activity.window.decorView
                val origin = IntArray(2).also(window::getLocationInWindow)
                val pixels = window.drawToBitmap()
                val x = map.center.x.toInt() - origin[0]
                val y = map.center.y.toInt() - origin[1]
                val offset = (map.width / 10).toInt()
                assertEquals(top, Color(pixels.getPixel(x, y - offset)))
                assertEquals(right, Color(pixels.getPixel(x + offset, y)))
            }
        }
        assertMarkerPixels(HudArrow, HudArrow)
        compose.runOnIdle { state.value = state.value.copy(mapBearingDeg = 0f) }
        assertMarkerPixels(HudArrow, Color.Black)
        compose.runOnIdle { state.value = state.value.copy(mapBearingDeg = 90f) }
        assertMarkerPixels(Color.Black, HudArrow)
    }
}
