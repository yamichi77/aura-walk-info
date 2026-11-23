package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.activity.ComponentActivity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavRouteOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun drawsRemainingLineDestinationRingAndTurnDotInEachMapMode() {
        val start = NavPoint(0.0, 0.0)
        val end = NavPoint(0.0, 0.01)
        val destination = NavPoint(0.01, 0.01)
        val turn = NavPoint(0.01, 0.0)
        val route = NavMapRoute(listOf(start, end), destination, turn, 0.6f)
        val mapModes = listOf(null, BigMapMode.FLAT, BigMapMode.TILTED)
        val spec = hudVisualSpecFor(
            hudTestState(MotionState.STOPPED).copy(mapCenter = null), navState = sampleGuiding(),
        ) as HudVisualSpec.Clusters
        val withRoute = spec.copy(navigation = spec.navigation?.copy(route = route))
        compose.setContent {
            Column {
                for (mode in mapModes) {
                    val y = if (mode == BigMapMode.TILTED) 60f else 55f
                    MiniMapPanel(
                        withRoute, HudDebugOptions(),
                        modifier = Modifier.size(80.dp).background(Color.Black).testTag(mode?.name ?: "MINI"),
                        bigMapMode = mode,
                        routeProjection = { points ->
                            points.map {
                                when (it) {
                                    start -> Offset(20f, y)
                                    end -> Offset(52f, y)
                                    destination -> Offset(47f, 20f)
                                    else -> Offset(29f, 20f)
                                }
                            }
                        },
                    )
                }
            }
        }
        for (mode in mapModes) {
            val tag = mode?.name ?: "MINI"
            val y = if (mode == BigMapMode.TILTED) 60 else 55
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                val window = compose.activity.window.decorView
                val origin = IntArray(2).also(window::getLocationInWindow)
                val bitmap = window.drawToBitmap()
                fun blue(x: Int, y: Int) = Color(bitmap.getPixel(
                    bounds.left.toInt() - origin[0] + x, bounds.top.toInt() - origin[1] + y,
                )).blue
                assertTrue("$tag line", blue(35, y) > 0.5f)
                if (mode != null) assertTrue("$tag 4px line", blue(35, y - 2) > 0.5f)
                else assertTrue("$tag 3px line", blue(35, y - 2) < 0.4f)
                assertEquals("$tag passed line", 0f, blue(15, y), 0f)
                assertTrue("$tag destination ring", blue(51, 20) > 0.4f)
                assertEquals("$tag empty destination center", 0f, blue(47, 20), 0f)
                assertTrue("$tag turn dot", blue(29, 20) > 0.8f)
            }
        }
    }
}
