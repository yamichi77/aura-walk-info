package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.safety.HudVisibility
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixedTime = ZonedDateTime.parse("2026-11-23T09:05:00+09:00")
    private val fixedClock = WallClock { fixedTime }

    @Test
    fun walkingShowsDistanceTimeAndOneLineAddress() {
        show(MotionState.WALKING)
        displayed("1.24", "km", "歩行 18:42", "みほん市試験町1丁目")
        timeIsAbove("1.24", "km", "歩行 18:42", "みほん市試験町1丁目")
        absent("経過 21:05 ・ 15'05\"/km", "GPS 測位中…", "高速移動中のため非表示")
    }

    @Test
    fun stoppedShowsElapsedPaceAndTwoLineAddress() {
        show(MotionState.STOPPED)
        val rows = arrayOf("1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "架空県みほん市", "試験町1丁目")
        displayed(*rows)
        timeIsAbove(*rows)
        val panel = compose.onNodeWithTag("stats-panel").getUnclippedBoundsInRoot()
        for (text in listOf("9:05", *rows)) {
            val row = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
            assertTrue("$text $row fits in $panel", row.left >= panel.left && row.top >= panel.top &&
                row.right <= panel.right && row.bottom <= panel.bottom)
            assertFalse("$text is clipped or ellipsized", textLayout(text).hasVisualOverflow)
        }
        absent("GPS 測位中…", "みほん市試験町1丁目")
    }

    @Test
    fun fastRemovesStatsAndAddress() {
        show(MotionState.FAST)
        displayed("高速移動中のため非表示")
        absent("9:05")
        absent("1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "みほん市試験町1丁目", "GPS 測位中…")
    }

    @Test
    fun fastRemovesActiveCountdown() {
        val motion = mutableStateOf(MotionState.WALKING)
        compose.setContent {
            AdditiveDisplayTheme {
                val state = hudTestState(motion.value).copy(mapCenter = null)
                val spec = hudVisualSpecFor(state, phase = WalkPhase.Starting(3_000), countdown = 3)
                PhoneScreen(spec, false, HudDebugOptions(), {}, false) {}
            }
        }
        val countdown = compose.onNodeWithTag("countdown")
        countdown.assertIsDisplayed()
        compose.runOnIdle { motion.value = MotionState.FAST }
        countdown.assertDoesNotExist()
        displayed("高速移動中のため非表示")
    }

    @Test
    fun noFixShowsPreviousStatsAndGpsMessage() {
        show(MotionState.NO_FIX)
        displayed("1.24", "km", "歩行 18:42", "みほん市試験町1丁目", "GPS 測位中…")
        timeIsAbove("1.24", "km", "歩行 18:42", "みほん市試験町1丁目", "GPS 測位中…")
        val style = textLayout("9:05").layoutInput.style
        assertEquals(Color(0xFFB5D4F4), style.color)
        assertEquals((16 * Layout.textScale(720f)).sp, style.fontSize)
        assertEquals(FontWeight.Normal, style.fontWeight)
        absent("経過 21:05 ・ 15'05\"/km")
    }

    @Test
    fun deniedPermissionShowsPermissionMessage() {
        show(MotionState.NO_FIX, denied = true)
        displayed("位置の権限がありません")
        absent("9:05", "1.24", "GPS 測位中…")
    }

    @Test
    fun walkControlsShowStandbyCountdownsSummaryAndFastOverride() {
        val phase = mutableStateOf<WalkPhase>(WalkPhase.Standby)
        val motion = mutableStateOf(MotionState.STOPPED)
        val dpPerMeter = 720f
        compose.setContent {
            AdditiveDisplayTheme {
                StatsPanel(
                    hudVisualSpecFor(hudTestState(motion.value), phase = phase.value, countdown = 3),
                    false, Layout.textScale(dpPerMeter),
                    Modifier.size(
                        (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                        (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                    ),
                    wallClock = fixedClock,
                )
            }
        }
        displayed("タップで開始", "みほん市試験町1丁目")
        timeIsAbove("タップで開始", "みほん市試験町1丁目")
        absent("1.24", "歩行 18:42", "架空県みほん市")
        compose.runOnIdle { phase.value = WalkPhase.Starting(3_000) }
        displayed("タップで開始", "みほん市試験町1丁目")
        absent("3", "タップでキャンセル", "1.24")
        compose.runOnIdle { phase.value = WalkPhase.Ending(6_000) }
        displayed("1.24", "歩行 18:42")
        absent("終了", "3", "タップでキャンセル")
        compose.runOnIdle { phase.value = WalkPhase.Summary }
        displayed("おつかれさま", "1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "タップで新しく開始")
        timeIsAbove("おつかれさま", "1.24", "km", "歩行 18:42", "経過 21:05 ・ 15'05\"/km", "タップで新しく開始")
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
        absent("9:05", "1.24", "歩行 18:42")

        compose.mainClock.advanceTimeBy(14_900)
        displayed(SafetyNotice.MESSAGE)
        assertFalse(notice.hasBeenShown)
        compose.mainClock.advanceTimeBy(150)
        absent(SafetyNotice.MESSAGE)
        displayed("9:05", "1.24", "歩行 18:42")

        compose.runOnIdle {
            val relaunchedNotice = SafetyNotice(context)
            visibility.value = HudVisibility(relaunchedNotice, !relaunchedNotice.hasBeenShown)
        }
        compose.mainClock.advanceTimeByFrame()
        absent(SafetyNotice.MESSAGE)
        displayed("1.24")
    }

    @Test
    fun readsClockAtTheMinuteBoundaryWithoutPollingOrStartingAnEffect() {
        var now = fixedTime.plusSeconds(57)
        var reads = 0
        compose.mainClock.autoAdvance = false
        show(MotionState.STOPPED, clock = WallClock { reads++; now })
        displayed("9:05")
        val initialReads = compose.runOnIdle { reads }
        compose.runOnIdle { now = now.plusSeconds(1) }
        compose.mainClock.advanceTimeBy(1_000)
        displayed("9:05")
        assertEquals(initialReads, compose.runOnIdle { reads })
        compose.runOnIdle { now = fixedTime.plusMinutes(1) }
        compose.mainClock.advanceTimeBy(2_050)
        displayed("9:06")
        absent("9:05")
        assertEquals(initialReads + 1, compose.runOnIdle { reads })
        compose.onNodeWithTag("quantum-label").assertDoesNotExist()
    }

    private fun show(
        motion: MotionState,
        denied: Boolean = false,
        clock: WallClock = fixedClock,
        visibility: () -> HudVisibility? = { null },
    ) {
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
                    ).clipToBounds().testTag("stats-panel"),
                    onNoticeTimeout = { hud?.dismissNotice() },
                    wallClock = clock,
                )
            }
        }
    }

    private fun timeIsAbove(vararg rows: String) {
        val time = compose.onNodeWithText("9:05").assertIsDisplayed().getUnclippedBoundsInRoot()
        for (text in rows) {
            val row = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
            assertTrue("9:05 starts above $text", time.top < row.top)
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun displayed(vararg strings: String) {
        for (text in strings) compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun absent(vararg strings: String) {
        for (text in strings) compose.onNodeWithText(text).assertDoesNotExist()
    }
}
