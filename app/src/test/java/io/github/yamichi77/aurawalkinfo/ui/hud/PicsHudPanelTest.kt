package io.github.yamichi77.aurawalkinfo.ui.hud

import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import io.github.yamichi77.aurawalkinfo.pics.PicsPacket
import io.github.yamichi77.aurawalkinfo.pics.PicsTracker
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PicsHudPanelTest {
    @get:Rule val compose = createComposeRule()

    private val origin = MapCenter(0.0, 0.0)
    private val intersection = MapCenter(0.00009, 0.0)
    private val candidate = mutableStateOf<PicsCandidate?>(null)
    private val motion = mutableStateOf(MotionState.STOPPED)
    private val phase = mutableStateOf<WalkPhase>(WalkPhase.Measuring)
    private val noticePending = mutableStateOf(false)
    private val denied = mutableStateOf(false)
    private val reference = PicsReferenceNotice(Clock { compose.mainClock.currentTime })
    private val red = listOf(PicsLight(PicsAspect.RED, 7), PicsLight(PicsAspect.GREEN, 3))
    private val freshColor = Color(0xFF9FE1CB)
    private val staleColor = Color(0xFF888780)
    private val referenceText = "信号は参考表示です"
    private val detail = "経過 21:05 ・ 15'05\"/km"

    @Test
    fun stoppedReplacesOnlyTheDetailAndFitsBothAddressLines() {
        reference.start()
        compose.mainClock.advanceTimeBy(6_001)
        candidate.value = sample(red)
        showStats()

        val panel = compose.onNodeWithTag("pics-stats-panel").getUnclippedBoundsInRoot()
        val rows = listOf("9:05", "1.24", "km", "歩行 18:42", "信号 赤7・青3", "架空県みほん市", "試験町1丁目")
        rows.forEach {
            val row = text(it).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$it $row fits in $panel", row.left >= panel.left && row.right <= panel.right &&
                row.top >= panel.top && row.bottom <= panel.bottom)
            assertFalse("$it has text overflow", textLayout(it).hasVisualOverflow)
        }
        text(detail).assertDoesNotExist()

        change { motion.value = MotionState.WALKING }
        compose.mainClock.advanceTimeBy(350)
        text("信号 赤7・青3").assertDoesNotExist()
        text("みほん市試験町1丁目").assertIsDisplayed()
    }

    @Test
    fun fastSafetyNoticeAndPermissionDenialRemoveTheSignalImmediately() {
        candidate.value = sample(red)
        showStats()
        text(referenceText).assertIsDisplayed()

        change { motion.value = MotionState.FAST }
        text("高速移動中のため非表示").assertIsDisplayed()
        listOf(referenceText, "信号 赤7・青3", "9:05", "1.24", "架空県みほん市").forEach {
            text(it).assertDoesNotExist()
        }

        change {
            motion.value = MotionState.STOPPED
            noticePending.value = true
        }
        text(SafetyNotice.MESSAGE).assertIsDisplayed()
        text(referenceText).assertDoesNotExist()

        change {
            noticePending.value = false
            denied.value = true
        }
        text("位置の権限がありません").assertIsDisplayed()
        text(referenceText).assertDoesNotExist()
    }

    @Test
    fun firstAppearanceUsesTheReferenceForSixSecondsAcrossPhaseChanges() {
        showStats()
        text(referenceText).assertDoesNotExist()
        change { candidate.value = sample(red) }
        assertEquals(freshColor, textLayout(referenceText).layoutInput.style.color)
        compose.mainClock.advanceTimeBy(5_800)
        change { phase.value = WalkPhase.Standby }
        text(referenceText).assertIsDisplayed()
        text("信号 赤7・青3").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(250)
        text(referenceText).assertDoesNotExist()
        text("信号 赤7・青3").assertIsDisplayed()
        change { candidate.value = null }
        compose.mainClock.advanceTimeBy(350)
        text("信号 赤7・青3").assertDoesNotExist()
        change { candidate.value = sample(red) }
        text("信号 赤7・青3").assertIsDisplayed()
        text(referenceText).assertDoesNotExist()
    }

    @Test
    fun lossOfSignalNearTheEndOfTheReferenceFadesOnlyTheReference() {
        candidate.value = sample(red)
        showStats()
        text(referenceText).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5_840)
        change { candidate.value = null }
        compose.mainClock.advanceTimeBy(170)
        text(referenceText).assertIsDisplayed()
        text("信号 赤7・青3").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(180)
        text(referenceText).assertDoesNotExist()
    }

    @Test
    fun freshnessAloneChangesColorAndExpiryUsesTheHudFade() {
        reference.start()
        compose.mainClock.advanceTimeBy(6_001)
        var now = 0L
        val tracker = PicsTracker(Clock { now })
        tracker.receive(PicsPacket.Position("DEADBEEF", intersection), -60)
        tracker.receive(PicsPacket.Signal("DEADBEEF", listOf(PicsLight(PicsAspect.RED, 7))), -60)
        candidate.value = tracker.nearest(MotionState.STOPPED, origin)
        showStats()
        assertEquals(freshColor, textLayout("信号 赤7").layoutInput.style.color)

        change {
            tracker.receive(PicsPacket.Signal("DEADBEEF", listOf(PicsLight(PicsAspect.GREEN, 3))), -60)
            candidate.value = tracker.nearest(MotionState.STOPPED, origin)
        }
        assertEquals(freshColor, textLayout("信号 青3").layoutInput.style.color)
        change {
            now = 1_500
            candidate.value = tracker.nearest(MotionState.STOPPED, origin)
        }
        assertEquals(staleColor, textLayout("信号 青3").layoutInput.style.color)
        change {
            now = 3_001
            candidate.value = tracker.nearest(MotionState.STOPPED, origin)
        }
        compose.mainClock.advanceTimeBy(150)
        text("信号 青3").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(200)
        text("信号 青3").assertDoesNotExist()
        text(detail).assertIsDisplayed()
    }

    @Test
    fun standbyAndSummaryAddOneRowBeforeAddressOrHint() {
        reference.start()
        compose.mainClock.advanceTimeBy(6_001)
        phase.value = WalkPhase.Standby
        showStats()
        text("みほん市試験町1丁目").assertIsDisplayed()
        text("信号 赤7・青3").assertDoesNotExist()
        change { candidate.value = sample(red) }
        assertAbove("タップで開始", "信号 赤7・青3")
        assertAbove("信号 赤7・青3", "みほん市試験町1丁目")

        change { phase.value = WalkPhase.Summary }
        assertAbove(detail, "信号 赤7・青3")
        assertAbove("信号 赤7・青3", "タップで新しく開始")
    }

    @Test
    fun mapDrawsATargetAtTheProjectedPointAndLabelsBothLargeModes() {
        lateinit var view: View
        val mode = mutableStateOf<BigMapMode?>(null)
        val point = mutableStateOf(IntOffset(106, 106))
        val display = mutableStateOf(sample(red + PicsLight(PicsAspect.FLASHING_GREEN, null)).toHudDisplay())
        compose.setContent {
            AdditiveDisplayTheme {
                view = LocalView.current
                PicsMapOverlay(display.value, point.value, 32.dp, mode.value, Modifier.size(212.dp))
            }
        }
        val mark = compose.onNodeWithTag("pics-map-mark")
        mark.assertIsDisplayed()
        val fresh = compose.runOnIdle { view.drawToBitmap().asImageBitmap().toPixelMap() }
        assertTrue("the ring surrounds the projected point", fresh[106, 90] != fresh[106, 106])
        assertEquals(Color(0xFFE07A7A), fresh[97, 129])
        assertEquals(Color(0xFF7FBFA8), fresh[106, 129])
        assertTrue("the flashing light has an empty center", fresh[115, 129] != Color(0xFF7FBFA8))
        text(display.value.label).assertDoesNotExist()

        for (large in BigMapMode.entries) {
            compose.runOnIdle { mode.value = large }
            mark.assertIsDisplayed()
            text(display.value.label).assertIsDisplayed()
        }
        compose.runOnIdle { display.value = display.value.copy(freshness = PicsFreshness.STALE) }
        val stale = compose.runOnIdle { view.drawToBitmap().asImageBitmap().toPixelMap() }
        for (x in listOf(97, 106, 115)) assertEquals(staleColor, stale[x, 129])

        compose.runOnIdle { point.value = IntOffset(10, 10) }
        mark.assertDoesNotExist()
        text(display.value.label).assertDoesNotExist()
    }

    private fun sample(lights: List<PicsLight>) =
        PicsCandidate("DEADBEEF", intersection, lights, PicsFreshness.FRESH, 0.0, 10.0)

    private fun showStats() {
        compose.mainClock.autoAdvance = false
        val dpPerMeter = 720f
        val clock = WallClock { ZonedDateTime.parse("2026-11-23T09:05:00+09:00") }
        compose.setContent {
            AdditiveDisplayTheme {
                CompositionLocalProvider(LocalPicsReferenceNotice provides reference) {
                    StatsPanel(
                        hudVisualSpecFor(
                            hudTestState(motion.value), noticePending = noticePending.value,
                            phase = phase.value, pics = candidate.value,
                        ),
                        denied.value, HudLayoutConfig.textScale(dpPerMeter),
                        Modifier.size(
                            (HudPlacement.statsWidthMeters * dpPerMeter).dp,
                            (HudPlacement.statsHeightMeters * dpPerMeter).dp,
                        ).clipToBounds().testTag("pics-stats-panel"),
                        wallClock = clock,
                    )
                }
            }
        }
    }

    private fun change(update: () -> Unit) {
        compose.runOnIdle(update)
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun textLayout(value: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        text(value).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertAbove(above: String, below: String) {
        val first = text(above).assertIsDisplayed().getUnclippedBoundsInRoot()
        val second = text(below).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$above should be above $below", first.top < second.top)
    }
}
