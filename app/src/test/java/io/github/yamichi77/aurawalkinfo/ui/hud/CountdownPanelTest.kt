package io.github.yamichi77.aurawalkinfo.ui.hud

import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
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
class CountdownPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titleDigitAndCancelHintFitWithinXrPanelWithMargin() {
        val now = SystemClock.elapsedRealtime()
        val phase = mutableStateOf<WalkPhase>(WalkPhase.Starting(now + 3_000))
        val seconds = mutableStateOf(3)
        // Use the XR sizing path and the same physical density as the other HUD layout tests.
        val dpPerMeter = 720f
        val width = (HudPlacement.countdownWidthMeters * dpPerMeter).dp
        val height = (HudPlacement.countdownHeightMeters * dpPerMeter).dp
        compose.setContent {
            AdditiveDisplayTheme {
                val spec = hudVisualSpecFor(
                    hudTestState(MotionState.STOPPED), phase = phase.value, countdown = seconds.value,
                )
                rememberCountdownPresentation(spec)?.let {
                    CountdownPanel(it, dpPerMeter, {}, Modifier.size(width, height))
                }
            }
        }

        val panel = compose.onNodeWithTag("countdown").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(width.value, (panel.right - panel.left).value, 1f)
        assertEquals(height.value, (panel.bottom - panel.top).value, 1f)
        fun checkBounds(node: SemanticsNodeInteraction) {
            val bounds = node.assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(
                "$bounds must fit strictly inside $panel",
                bounds.left > panel.left && bounds.top > panel.top &&
                    bounds.right < panel.right && bounds.bottom < panel.bottom,
            )
        }
        checkBounds(compose.onNodeWithText("開始", useUnmergedTree = true))
        checkBounds(compose.onNodeWithContentDescription("3", useUnmergedTree = true))
        checkBounds(compose.onNodeWithText("タップでキャンセル", useUnmergedTree = true))

        compose.runOnIdle {
            phase.value = WalkPhase.Ending(now + 6_000)
            seconds.value = 1
        }
        checkBounds(compose.onNodeWithText("終了", useUnmergedTree = true))
        checkBounds(compose.onNodeWithContentDescription("1", useUnmergedTree = true))
        checkBounds(compose.onNodeWithText("タップでキャンセル", useUnmergedTree = true))
    }
}
