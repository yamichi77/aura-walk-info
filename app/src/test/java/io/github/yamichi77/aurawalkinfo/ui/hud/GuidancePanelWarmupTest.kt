package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
class GuidancePanelWarmupTest {
    @get:Rule val compose = createComposeRule()

    private val idle = hudVisualSpecFor(hudTestState(MotionState.WALKING)) as HudVisualSpec.Clusters
    private val guiding = sampleGuiding()
    private val visual = checkNotNull(navHudVisualFor(guiding, noFix = false, gpsCourseDeg = 0f))
    private val shown = skyBandFrame(false, true, true, NorthEstimate(0.0, 5.0), 0.0,
        SkyBandTargets(skyTestGuidance(-12.0, 10.0), skyTestSignal(20.0)))
    private val startup = QuantumAnimation(QuantumEffect.Startup, mutableFloatStateOf(0f))
    private val quantum = mutableStateOf(HudQuantum(startup, startup))
    private val spec = mutableStateOf<HudVisualSpec>(idle)
    private val skyFrame = mutableStateOf(SkyBandFrame(SkyBandStatus.NO_TARGETS))
    private val open = mutableStateOf(false)
    private val creationFrames = mutableListOf<Long>()
    private val sky = mutableMapOf<SkyMarkKind, SkyBandSlot>()
    private var nav: HudSurface? = null
    private val dpPerMeter = 500f
    private val mapSize = HudPlacement.mapSizeMeters(idle.mapDiameter)

    @Test
    fun createsFiveHiddenPanelsAfterStartupAndReusesThemThroughGuidanceAndFastMotion() {
        render()
        advance(500)
        assertTrue(creationFrames.isEmpty())
        change { quantum.value = HudQuantum(null, startup) }
        advance(500)
        assertTrue(creationFrames.isEmpty())
        change { quantum.value = HudQuantum(null, null) }
        advance(48)
        assertTrue(creationFrames.isEmpty()) // Three smooth frame intervals must pass first.
        advance(160)
        assertEquals(5, creationFrames.size)
        assertEquals(5, creationFrames.distinct().size)
        assertEquals(SkyPanelSlots.toSet(), sky.keys)
        assertAllHidden()

        change {
            open.value = true
            spec.value = idle.copy(navigation = visual)
            skyFrame.value = shown
        }
        advance(400)
        assertSkyPositions()
        assertNavHidden() // Starting navigation while the large map is open leaves the shell empty.
        assertEquals(5, creationFrames.size)

        change {
            spec.value = HudVisualSpec.Fast
            skyFrame.value = SkyBandFrame(SkyBandStatus.FAST)
            open.value = false
        }
        advance(16)
        assertAllHidden()
        change {
            spec.value = idle.copy(navigation = visual)
            skyFrame.value = shown
        }
        advance(400)
        assertSkyPositions()
        compose.onNodeWithText(visual.primary).assertIsDisplayed()
        change {
            spec.value = idle
            skyFrame.value = SkyBandFrame(SkyBandStatus.NO_TARGETS)
        }
        advance(400)
        assertAllHidden()
        assertEquals(5, creationFrames.size)
    }

    @Test
    fun earlyStartDefersCreationAndMountsTheSkyBandOnePanelPerFrame() {
        val preview = NavState.Preview(guiding.destination, guiding.route, syntheticFix(0))
        spec.value = idle.copy(destinationPrompt = DestinationPrompt.Preview(preview))
        open.value = true
        render()
        advance(64)
        assertTrue(creationFrames.isEmpty())
        change {
            spec.value = idle.copy(navigation = visual)
            skyFrame.value = shown
        }
        advance(16)
        assertTrue(creationFrames.isEmpty()) // The Preview-to-Guiding recomposition.
        for (count in 1..4) {
            advance(16)
            assertEquals(count, creationFrames.size)
        }
        assertEquals(4, creationFrames.distinct().size)
        assertEquals(null, nav)
        advance(350)
        assertSkyPositions()

        change { quantum.value = HudQuantum(null, null) }
        advance(160)
        assertEquals(5, creationFrames.size)
        assertNavHidden()
        change { open.value = false }
        advance(1_500)
        compose.onNodeWithText(visual.primary).assertIsDisplayed()
        assertEquals(5, creationFrames.size)
    }

    @Test
    fun fixedRasterPreservesTextAndArrowPositionsInBothModes() {
        quantum.value = HudQuantum(null, null)
        spec.value = idle.copy(navigation = visual)
        render(compareWithOriginal = true)
        advance(200)
        val primary = hasText(visual.primary)
        val secondary = hasText(checkNotNull(visual.secondary))
        val arrow = hasTestTag("nav_arrow")
        for (item in listOf(primary, secondary, arrow)) assertAtOriginalPosition(item, withArrow = true)
        val rasterWidth = checkNotNull(nav).width

        change { spec.value = idle.copy(navigation = visual.copy(arrow = null)) }
        advance(32)
        for (item in listOf(primary, secondary)) assertAtOriginalPosition(item, withArrow = false)
        compose.onNode(arrow and hasAnyAncestor(hasTestTag("actual"))).assertDoesNotExist()
        assertEquals(rasterWidth, checkNotNull(nav).width, 0f)
        assertEquals(5, creationFrames.size)
    }

    private fun render(compareWithOriginal: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val slots = rememberGuidancePanelSlots(spec.value, quantum.value, false, open.value, skyFrame)
            Column {
                Row {
                    SkyBandPanels(skyFrame, slots.skyCount) { slot ->
                        DisposableEffect(slot) {
                            creationFrames += compose.mainClock.currentTime
                            sky[slot.kind] = slot
                            onDispose { sky.remove(slot.kind) }
                        }
                        slot.mark.value?.let { SkyMarkContent(it, slot.alpha(), 20f, Modifier.size(64.dp)) }
                    }
                }
                SpatialHudPanels(
                    dpPerMeter, 1f, spec.value, false, HudDebugOptions(), 1f, true, {}, open.value, {},
                    true, null, quantum.value, WallClock { ZonedDateTime.now() }, BigMapMode.FLAT, null, null,
                    remember { BigMapViewport() }, {}, {}, slots.navReady,
                ) { surface, content ->
                    if (surface.height == HudPlacement.navHeightMeters) {
                        DisposableEffect(Unit) {
                            creationFrames += compose.mainClock.currentTime
                            onDispose { nav = null }
                        }
                        SideEffect { nav = surface }
                        Box(Modifier.size((surface.width * dpPerMeter).dp, (surface.height * dpPerMeter).dp)
                            .testTag("actual")) { content() }
                    }
                }
                if (compareWithOriginal) {
                    val reference = checkNotNull((spec.value as HudVisualSpec.Clusters).navigation)
                    Box(Modifier.size((HudPlacement.navWidthMeters(reference.arrow != null) * dpPerMeter).dp,
                        (HudPlacement.navHeightMeters * dpPerMeter).dp).testTag("original"),
                        contentAlignment = Alignment.Center) {
                        NavGuidanceContent(reference, idle.opacity, dpPerMeter) { null }
                    }
                }
            }
        }
    }

    private fun assertAtOriginalPosition(item: SemanticsMatcher, withArrow: Boolean) {
        val panel = checkNotNull(nav)
        assertEquals(HudPlacement.navPose(mapSize, withArrow), panel.pose)
        val actual = compose.onNode(item and hasAnyAncestor(hasTestTag("actual")))
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val original = compose.onNode(item and hasAnyAncestor(hasTestTag("original")))
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val actualPanel = compose.onNodeWithTag("actual").getUnclippedBoundsInRoot()
        val originalPanel = compose.onNodeWithTag("original").getUnclippedBoundsInRoot()
        assertEquals((original.right - original.left).value, (actual.right - actual.left).value, 0f)
        assertEquals((original.bottom - original.top).value, (actual.bottom - actual.top).value, 0f)
        assertEquals((original.left - (originalPanel.right + originalPanel.left) / 2).value,
            (actual.left - (actualPanel.right + actualPanel.left) / 2).value, 0.5f)
        assertEquals((original.top - originalPanel.top).value, (actual.top - actualPanel.top).value, 0f)
    }

    private fun assertSkyPositions() {
        for (mark in shown.marks) {
            compose.onNodeWithTag("sky-${mark.kind.name}").assertIsDisplayed()
            val slot = checkNotNull(sky[mark.kind])
            assertEquals(mark, slot.mark.value)
            assertEquals(HudPlacement.pose(mark.yaw, skyMarkLayout(mark.kind).pitch), slot.pose())
        }
    }

    private fun assertAllHidden() {
        assertNavHidden()
        assertTrue(sky.values.all { it.mark.value == null && it.alpha() == 0f })
    }

    private fun assertNavHidden() {
        val panel = checkNotNull(nav)
        assertTrue(!panel.enabled && panel.alpha == 0f)
        compose.onNodeWithText(visual.primary).assertDoesNotExist()
        compose.onNodeWithTag("nav_arrow").assertDoesNotExist()
    }

    private fun change(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
}
