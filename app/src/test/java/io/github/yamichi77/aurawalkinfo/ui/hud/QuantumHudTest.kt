package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
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
class QuantumHudTest {
    @get:Rule val compose = createComposeRule()

    private val state = mutableStateOf(hudTestState(MotionState.STOPPED))
    private val phase = mutableStateOf<WalkPhase>(WalkPhase.Standby)
    private val notice = mutableStateOf(false)
    private val map = mutableStateOf(BigMapControl())

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
        tag("quantum-map").assertDoesNotExist()
        settle()
        noEffects()
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
        settle()
        noEffects()
        text("おつかれさま", "1.30", "km", "歩行 18:43", "タップで新しく開始")
    }

    @Test
    fun mapOpensClosesByTapAndTimeoutAndFastImmediatelyRemovesTheEffects() {
        show()
        settle()
        tag("map").performClick()
        frames()
        effect("bigmap")
        settle()
        noEffects()
        tag("bigmap").assertIsDisplayed()
        tag("map").assertDoesNotExist()

        tag("bigmap").performClick()
        frames()
        effect("bigmap")
        effect("map")
        settle()
        noEffects()
        tag("map").assertIsDisplayed()
        tag("bigmap").assertDoesNotExist()

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
                val spec = hudVisualSpecFor(state.value, noticePending = notice.value, phase = phase.value)
                PhoneScreen(spec, false, HudDebugOptions(), {}, map.value.isOpen, quantumEnabled = enabled) {
                    map.value = map.value.tap(it, 0, state.value.motionState)
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
    private fun effect(panel: String) = tag("quantum-$panel").assertExists()
    private fun noEffects() = listOf("left", "map", "bigmap").forEach {
        tag("quantum-$it").assertDoesNotExist()
    }

    private fun text(vararg values: String) = values.forEach {
        compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
    }
}
