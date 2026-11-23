package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BigMapPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mapClickReplacesMiniMapAndMapOrOutsideClickClosesIt() {
        val map = mutableStateOf(BigMapControl())
        val state = hudTestState(MotionState.STOPPED).copy(mapCenter = null)
        compose.setContent {
            AdditiveDisplayTheme {
                PhoneScreen(hudVisualSpecFor(state), false, HudDebugOptions(), {}, map.value.isOpen) { target ->
                    map.value = map.value.tap(target, 0, state.motionState)
                }
            }
        }
        val mini = compose.onNodeWithTag("map")
        val large = compose.onNodeWithTag("bigmap")
        val outside = compose.onNodeWithTag("outside")
        mini.assertIsDisplayed().performClick()
        large.assertIsDisplayed()
        mini.assertDoesNotExist()
        large.performClick()
        mini.assertIsDisplayed()
        large.assertDoesNotExist()
        mini.performClick()
        outside.assertIsDisplayed().performClick()
        large.assertDoesNotExist()
        outside.assertDoesNotExist()
        mini.assertIsDisplayed()
    }
}
