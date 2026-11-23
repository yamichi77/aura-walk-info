package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.github.yamichi77.aurawalkinfo.safety.HudVisibility
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
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

    @Test
    fun walkControlsShowStandbyCountdownsSummaryAndFastOverride() {
        val phase = mutableStateOf<WalkPhase>(WalkPhase.Standby)
        val motion = mutableStateOf(MotionState.STOPPED)
        val seconds = mutableStateOf(3)
        val dpPerMeter = 720f
        compose.setContent {
            AdditiveDisplayTheme {
                StatsPanel(
                    hudVisualSpecFor(hudTestState(motion.value), phase = phase.value, countdown = seconds.value),
                    false, Layout.textScale(dpPerMeter),
                    Modifier.size(
                        (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                        (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                    ),
                )
            }
        }
        displayed("タップで開始", "みほん市試験町1丁目")
        absent("1.24", "歩行 18:42", "架空県みほん市")
        compose.runOnIdle { phase.value = WalkPhase.Starting(3_000) }
        displayed("3", "タップでキャンセル")
        compose.runOnIdle { seconds.value = 2 }
        displayed("2")
        compose.runOnIdle { seconds.value = 1 }
        displayed("1")
        compose.runOnIdle { phase.value = WalkPhase.Ending(6_000) }
        displayed("終了 1", "タップでキャンセル")
        compose.runOnIdle { phase.value = WalkPhase.Summary }
        displayed("おつかれさま", "1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "タップで新しく開始")
        absent("タップでキャンセル", "みほん市試験町1丁目")
        compose.runOnIdle { motion.value = MotionState.FAST }
        displayed("高速移動中のため非表示")
        absent("おつかれさま", "タップで新しく開始")
    }

    @Test
    fun firstLaunchShowsNoticeInStatsPanelThenPersistsDismissalAfterFifteenSeconds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notice = SafetyNotice(context)
        val visibility = mutableStateOf(HudVisibility(notice, !notice.hasBeenShown))
        compose.mainClock.autoAdvance = false
        show(MotionState.WALKING, visibility = { visibility.value })
        displayed(SafetyNotice.MESSAGE)
        absent("1.24", "歩行 18:42")

        compose.mainClock.advanceTimeBy(14_900)
        displayed(SafetyNotice.MESSAGE)
        assertFalse(notice.hasBeenShown)
        compose.mainClock.advanceTimeBy(150)
        absent(SafetyNotice.MESSAGE)
        displayed("1.24", "歩行 18:42")

        compose.runOnIdle {
            val relaunchedNotice = SafetyNotice(context)
            visibility.value = HudVisibility(relaunchedNotice, !relaunchedNotice.hasBeenShown)
        }
        compose.mainClock.advanceTimeByFrame()
        absent(SafetyNotice.MESSAGE)
        displayed("1.24")
    }

    private fun show(motion: MotionState, denied: Boolean = false, visibility: () -> HudVisibility? = { null }) {
        val dpPerMeter = 720f
        compose.setContent {
            AdditiveDisplayTheme {
                val hud = visibility()
                StatsPanel(
                    hudVisualSpecFor(hudTestState(motion), hud?.hidden == true, hud?.noticePending == true),
                    denied, Layout.textScale(dpPerMeter),
                    Modifier.size(
                        (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                        (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                    ),
                    onNoticeTimeout = { hud?.dismissNotice() },
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
