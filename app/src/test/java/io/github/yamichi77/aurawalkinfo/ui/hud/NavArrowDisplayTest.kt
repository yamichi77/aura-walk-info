package io.github.yamichi77.aurawalkinfo.ui.hud

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavArrowDisplayTest {
    @get:Rule val compose = createComposeRule()

    private val guiding = sampleGuiding()
    private val nav = mutableStateOf<NavState>(guiding)
    private val motion = mutableStateOf(MotionState.WALKING)
    private val gpsCourse = mutableStateOf<Float?>(0f)
    private val bigMapOpen = mutableStateOf(false)
    private lateinit var view: View

    @Test
    fun showsArrowLeftOfBothLinesAndKeepsLinesWithoutReference() {
        gpsCourse.value = null
        render(headBearing = { null })
        val primary = compose.onNodeWithText("40m先 右")
        val secondary = compose.onNodeWithText("残り 91m・2分")
        primary.assertIsDisplayed()
        secondary.assertIsDisplayed()
        arrow().assertDoesNotExist()

        compose.runOnIdle { gpsCourse.value = 0f }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("nav_arrow")).fetchSemanticsNodes().isNotEmpty() }
        val bounds = arrow().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(bounds.right < primary.getUnclippedBoundsInRoot().left)
        assertTrue(bounds.right < secondary.getUnclippedBoundsInRoot().left)
    }

    @Test
    fun drawsCyanThenGrayAndRemovesArrowDuringFastMotionAndArrival() {
        render(headBearing = { 0.0 })
        assertArrowStroke(red = 89, green = 153, blue = 179)

        compose.runOnIdle { motion.value = MotionState.NO_FIX }
        assertArrowStroke(red = 95, green = 95, blue = 90)

        compose.runOnIdle { motion.value = MotionState.FAST }
        arrow().assertDoesNotExist()

        compose.runOnIdle {
            motion.value = MotionState.WALKING
            nav.value = NavState.Arrived(guiding.destination)
        }
        compose.onNodeWithText("到着しました").assertIsDisplayed()
        arrow().assertDoesNotExist()
    }

    @Test
    fun openBigMapHidesBothGuidanceLinesAndTheEdgeArrow() {
        render(headBearing = { 0.0 })
        val primary = compose.onNodeWithText("40m先 右")
        val secondary = compose.onNodeWithText("残り 91m・2分")
        primary.assertIsDisplayed()
        secondary.assertIsDisplayed()
        arrow().assertIsDisplayed()
        compose.runOnIdle { bigMapOpen.value = true }
        primary.assertDoesNotExist()
        secondary.assertDoesNotExist()
        arrow().assertDoesNotExist()
    }

    private fun render(headBearing: (Long) -> Double?) {
        compose.setContent {
            AdditiveDisplayTheme {
                view = LocalView.current
                val spec = hudVisualSpecFor(
                    hudTestState(motion.value).copy(gpsCourseDeg = gpsCourse.value),
                    navState = nav.value,
                )
                if (spec is HudVisualSpec.Clusters) {
                    spec.rightClusterNavigation(bigMapOpen.value)?.let {
                        Box(Modifier.width(360.dp).background(Color.Black)) {
                            NavGuidanceContent(it, spec.opacity, dpPerMeter = 1_000f, headBearing = headBearing)
                        }
                    }
                }
            }
        }
    }

    private fun arrow() = compose.onNodeWithTag("nav_arrow")

    private fun assertArrowStroke(red: Int, green: Int, blue: Int) {
        val bounds = arrow().assertIsDisplayed().getUnclippedBoundsInRoot()
        val pixels = compose.runOnIdle { view.drawToBitmap().asImageBitmap().toPixelMap() }
        val colors = (bounds.top.value.toInt() until bounds.bottom.value.toInt()).flatMap { y ->
            (bounds.left.value.toInt() until bounds.right.value.toInt()).map { x -> pixels[x, y] }
        }
        assertTrue("expected a stroke near ($red, $green, $blue); brightest pixel=${colors.maxBy { it.red + it.green + it.blue }}",
            colors.any {
                abs(it.red * 255 - red) <= 3 && abs(it.green * 255 - green) <= 3 && abs(it.blue * 255 - blue) <= 3
            },
        )
    }
}
