package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import java.time.ZonedDateTime
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
class QuantumHudTest {
    @get:Rule val compose = createComposeRule()

    private val state = mutableStateOf(hudTestState(MotionState.STOPPED))
    private val phase = mutableStateOf<WalkPhase>(WalkPhase.Standby)
    private val notice = mutableStateOf(false)
    private val prompt = mutableStateOf<DestinationPrompt>(DestinationPrompt.Map)
    private val map = mutableStateOf(BigMapControl())
    private var now = ZonedDateTime.parse("2026-11-23T09:05:00+09:00")
    private val wallClock = WallClock { now }

    @Test
    fun startupSkipsTheNoticeAndPlaysOnceThenDisplaysTheSameStandbyHud() {
        notice.value = true
        show()
        text(SafetyNotice.MESSAGE)
        tag("quantum-left").assertDoesNotExist()
        effect("map")
        settle()
        noEffects()
        change { notice.value = false }
        effect("left")
        animatedTime("9:05").assertExists()
        tag("quantum-map").assertDoesNotExist()
        settle()
        noEffects()
        updatedTimeWithoutEffect("9:05")
        text("タップで開始", "みほん市試験町1丁目")
        tag("map").assertIsDisplayed()
        change { state.value = state.value.copy(motionState = MotionState.FAST) }
        change { state.value = state.value.copy(motionState = MotionState.NO_FIX) }
        noEffects()
        text("タップで開始")
    }

    @Test
    fun completedCountdownsRevealStatsAndSummaryWithoutAnimatingOngoingUpdates() {
        show()
        settle()
        change { phase.value = WalkPhase.Starting(SystemClock.elapsedRealtime()) }
        compose.mainClock.advanceTimeBy(220)
        change { phase.value = WalkPhase.Measuring }
        tag("countdown-ripple").assertIsDisplayed()
        tag("quantum-left").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(380)
        tag("countdown-ripple").assertDoesNotExist()
        effect("left")
        updatedTimeWithoutEffect("9:05")
        settle()
        noEffects()
        text("1.24", "km", "歩行 18:42")

        change {
            state.value = state.value.copy(distanceMeters = 1_300.0, walkingTimeMillis = 1_123_200)
        }
        noEffects()
        text("1.30", "歩行 18:43")
        change { state.value = state.value.copy(motionState = MotionState.WALKING) }
        noEffects()
        change { state.value = state.value.copy(motionState = MotionState.NO_FIX) }
        noEffects()
        text("GPS 測位中…")

        change { phase.value = WalkPhase.Ending(SystemClock.elapsedRealtime() + 3_000) }
        change { phase.value = WalkPhase.Measuring }
        noEffects()
        change { phase.value = WalkPhase.Ending(SystemClock.elapsedRealtime()) }
        compose.mainClock.advanceTimeBy(220)
        change { phase.value = WalkPhase.Summary }
        compose.mainClock.advanceTimeBy(380)
        effect("left")
        updatedTimeWithoutEffect("9:05")
        settle()
        noEffects()
        text("おつかれさま", "1.30", "km", "歩行 18:43", "タップで新しく開始")
    }

    @Test
    fun minuteChangeDoesNotTriggerAQuantumEffect() {
        now = now.withSecond(58)
        show()
        settle()
        updatedTimeWithoutEffect("9:05")
        compose.runOnIdle { now = now.plusMinutes(1).withSecond(0) }
        compose.mainClock.advanceTimeBy(1_200)
        updatedTimeWithoutEffect("9:06")
        noEffects()
    }

    @Test
    fun mapOpensClosesByTapAndTimeoutAndFastImmediatelyRemovesTheEffects() {
        show()
        settle()
        tag("map").performClick()
        frames()
        effect("bigmap")
        compose.mainClock.advanceTimeBy(QuantumTransitionMillis + 1_100L)
        noEffects()
        tag("bigmap").assertIsDisplayed()
        tag("nav_destination").assertIsDisplayed()
        tag("bigmap_mode").assertIsDisplayed()
        tag("map").assertDoesNotExist()

        tag("bigmap").performClick()
        frames()
        effect("bigmap")
        effect("map")
        tag("nav_destination").assertIsNotEnabled()
        tag("bigmap_mode").assertIsNotEnabled()
        settle()
        noEffects()
        tag("map").assertIsDisplayed()
        tag("bigmap").assertDoesNotExist()
        tag("nav_destination").assertDoesNotExist()
        tag("bigmap_mode").assertDoesNotExist()

        tag("map").performClick()
        frames()
        settle()
        change { map.value = map.value.advance(checkNotNull(map.value.closesAt), state.value.motionState) }
        effect("bigmap")
        effect("map")
        change {
            state.value = state.value.copy(motionState = MotionState.FAST)
            map.value = map.value.advance(0, MotionState.FAST)
        }
        noEffects()
        tag("map").assertDoesNotExist()
        tag("bigmap").assertDoesNotExist()
        tag("outside").assertDoesNotExist()
        compose.onNodeWithText("タップで開始").assertDoesNotExist()
        text("高速移動中のため非表示")

        change { state.value = state.value.copy(motionState = MotionState.STOPPED) }
        noEffects()
        tag("map").assertIsDisplayed()
        tag("bigmap").assertDoesNotExist()
    }

    @Test
    fun mapButtonsGatherThenOrbitOnceAndRippleOnlyOnPinch() {
        show()
        settle()
        tag("map").performClick()
        frames()
        for (button in listOf("nav_destination", "bigmap_mode")) {
            effect("$button-gather")
            tag("quantum-$button-orbit").assertDoesNotExist()
        }
        compose.mainClock.advanceTimeBy(550)
        for (button in listOf("nav_destination", "bigmap_mode")) {
            tag("quantum-$button-gather").assertDoesNotExist()
            effect("$button-orbit")
        }
        compose.mainClock.advanceTimeBy(1_000)
        noEffects()
        tag("bigmap_mode").performClick()
        frames()
        effect("bigmap_mode-ripple")
        tag("quantum-nav_destination-ripple").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(520)
        noEffects()

        tag("nav_destination").performClick()
        frames()
        effect("nav_destination-ripple")
        tag("nav_destination").assertDoesNotExist()
        change {
            state.value = state.value.copy(motionState = MotionState.FAST)
            map.value = map.value.advance(0, MotionState.FAST)
        }
        noEffects()
        tag("nav_destination").assertDoesNotExist()
        tag("bigmap_mode").assertDoesNotExist()
    }

    @Test
    fun disabledUsesOrdinaryTextAndMapFade() {
        show(enabled = false)
        noEffects()
        text("タップで開始")
        change { phase.value = WalkPhase.Starting(SystemClock.elapsedRealtime()) }
        compose.mainClock.advanceTimeBy(220)
        text("3")
        change { phase.value = WalkPhase.Measuring }
        noEffects()
        text("1.24", "km", "歩行 18:42")
        change { phase.value = WalkPhase.Ending(SystemClock.elapsedRealtime()) }
        change { phase.value = WalkPhase.Summary }
        noEffects()
        text("おつかれさま", "タップで新しく開始")
        settle()

        tag("map").performClick()
        frames()
        noEffects()
        tag("bigmap").assertIsDisplayed()
        tag("bigmap_mode").assertIsDisplayed().performClick()
        frames()
        noEffects()
        tag("bigmap").performClick()
        frames()
        noEffects()
        tag("bigmap").assertExists() // The original fade keeps the non-clickable map mounted.
        settle()
        tag("bigmap").assertDoesNotExist()
        tag("map").assertIsDisplayed()
    }

    @Test
    fun glyphSamplingReadsJapaneseAndNumericOutlines() {
        val paint = Paint().apply { textSize = 128f }
        for (text in listOf("おつかれさま", "1.24")) {
            val particles = sampleQuantumPath(quantumTextPath(text, paint), 60, 13)
            assertEquals(60, particles.size)
            assertTrue(particles.maxOf { it.targetX } - particles.minOf { it.targetX } > 128f)
            assertTrue(particles.maxOf { it.targetY } - particles.minOf { it.targetY } > 30f)
        }
    }

    private fun show(enabled: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AdditiveDisplayTheme {
                val spec = hudVisualSpecFor(
                    state.value, noticePending = notice.value, phase = phase.value, destinationPrompt = prompt.value,
                )
                PhoneScreen(
                    spec, false, HudDebugOptions(), {}, map.value.isOpen,
                    quantumEnabled = enabled, wallClock = wallClock,
                ) {
                    if (it == HudTarget.NAV_DESTINATION) prompt.value = DestinationPrompt.Listening()
                    else map.value = map.value.tap(it, 0, state.value.motionState)
                }
            }
        }
        frames()
    }

    private fun change(block: () -> Unit) {
        compose.runOnIdle(block)
        frames()
    }

    private fun frames() {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }
    private fun settle() = compose.mainClock.advanceTimeBy(900)
    private fun tag(name: String) = compose.onNodeWithTag(name, useUnmergedTree = true)
    private fun animatedTime(time: String) =
        compose.onNode(hasText(time) and hasTestTag("quantum-label"), useUnmergedTree = true)
    private fun updatedTimeWithoutEffect(time: String) {
        text(time)
        animatedTime(time).assertDoesNotExist()
    }
    private fun effect(panel: String) = tag("quantum-$panel").assertExists()
    private fun noEffects() {
        for (panel in listOf("left", "map", "bigmap")) tag("quantum-$panel").assertDoesNotExist()
        for (button in listOf("nav_destination", "bigmap_mode")) {
            for (effect in listOf("gather", "orbit", "ripple")) tag("quantum-$button-$effect").assertDoesNotExist()
        }
    }

    private fun text(vararg values: String) = values.forEach {
        compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
    }
}
