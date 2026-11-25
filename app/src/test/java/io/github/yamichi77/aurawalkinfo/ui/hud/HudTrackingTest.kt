package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.spatial.MarkReason
import io.github.yamichi77.aurawalkinfo.spatial.spatialBlocker
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
class HudTrackingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun clustersStayVisibleBeforeTrackingAndAfterTrackingLoss() {
        val tracking = mutableStateOf(false)
        val spec = hudVisualSpecFor(hudTestState(MotionState.STOPPED))
        val targets = SkyBandTargets(skyTestGuidance(0.0, 10.0), null)
        val north = NorthEstimate(0.0, 5.0)
        val clock = WallClock { ZonedDateTime.parse("2026-11-24T12:34:00+09:00") }
        compose.setContent {
            Column {
                val frame = skyBandFrame(false, tracking.value, true, north, 0.0, targets)
                if (rememberSkyBandVisibility(rememberUpdatedState(frame)).value != null) Text("sky band")
                SpatialHudPanels(
                    dpPerMeter = 350f,
                    scale = 1f,
                    spec = spec,
                    locationDenied = false,
                    debug = HudDebugOptions(),
                    motionOpacity = 1f,
                    tracking = tracking.value,
                    onNoticeTimeout = {},
                    bigMapOpen = false,
                    onTap = {},
                    inputEnabled = true,
                    countdown = null,
                    quantum = HudQuantum(null, null),
                    wallClock = clock,
                    bigMapMode = BigMapMode.FLAT,
                    headingTracking = null,
                    geospatialSettings = null,
                    viewport = remember { BigMapViewport() },
                    onMapPan = {},
                    onMapHold = {},
                ) { surface, content ->
                    if (surface.enabled) Box(Modifier.size((surface.width * 350 * surface.visualScale).dp,
                        (surface.height * 350 * surface.visualScale).dp)) { content() }
                }
            }
        }

        fun assertClustersWithoutTracking() {
            compose.onNodeWithText("1.24").assertIsDisplayed()
            compose.onNodeWithTag("map").assertIsDisplayed()
            compose.onNodeWithText("sky band").assertDoesNotExist()
            // SpatialMarks runs outside HudScene and passes its own tracking state to this gate.
            assertEquals(
                MarkReason.TRACKING_LOST,
                spatialBlocker(MotionState.STOPPED, true, tracking.value, north, NavPoint(0.0, 0.0), 5f),
            )
        }

        assertClustersWithoutTracking()
        compose.runOnIdle { tracking.value = true }
        compose.mainClock.advanceTimeBy(350L)
        compose.onNodeWithText("sky band").assertIsDisplayed()
        compose.runOnIdle { tracking.value = false }
        compose.mainClock.advanceTimeBy(350L)
        assertClustersWithoutTracking()
    }
}
