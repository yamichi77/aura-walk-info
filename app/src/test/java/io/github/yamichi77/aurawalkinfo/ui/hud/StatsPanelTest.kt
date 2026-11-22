package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun walkingShowsDistanceTimeAndOneLineAddress() {
        show(MotionState.WALKING)
        displayed("1.24", "km", "歩行 18:42", "みほん市試験町1丁目")
        absent("経過 21:05 ・ 15'05\"/km", "GPS 測位中…", "高速移動中のため非表示")
    }

    @Test
    fun stoppedShowsElapsedPaceAndTwoLineAddress() {
        show(MotionState.STOPPED)
        displayed("1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "架空県みほん市", "試験町1丁目")
        absent("GPS 測位中…", "みほん市試験町1丁目")
    }

    @Test
    fun fastRemovesStatsAndAddress() {
        show(MotionState.FAST)
        displayed("高速移動中のため非表示")
        absent("1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "みほん市試験町1丁目", "GPS 測位中…")
    }

    @Test
    fun noFixShowsPreviousStatsAndGpsMessage() {
        show(MotionState.NO_FIX)
        displayed("1.24", "km", "歩行 18:42", "みほん市試験町1丁目", "GPS 測位中…")
        absent("経過 21:05 ・ 15'05\"/km")
    }

    @Test
    fun deniedPermissionShowsPermissionMessage() {
        show(MotionState.NO_FIX, denied = true)
        displayed("位置の権限がありません")
        absent("1.24", "GPS 測位中…")
    }

    private fun show(motion: MotionState, denied: Boolean = false) {
        val dpPerMeter = 720f
        compose.setContent {
            AdditiveDisplayTheme {
                StatsPanel(
                    hudVisualSpecFor(hudTestState(motion)), denied, Layout.textScale(dpPerMeter),
                    Modifier.size(
                        (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                        (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                    ),
                )
            }
        }
    }

    private fun displayed(vararg strings: String) {
        for (text in strings) compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun absent(vararg strings: String) {
        for (text in strings) compose.onNodeWithText(text).assertDoesNotExist()
    }
}
