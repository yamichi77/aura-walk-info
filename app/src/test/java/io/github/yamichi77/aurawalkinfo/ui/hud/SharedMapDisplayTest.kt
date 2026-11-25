package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.maps.android.compose.internal.DefaultGoogleMapsInitializer
import com.google.maps.android.compose.internal.GoogleMapsInitializer
import com.google.maps.android.compose.internal.InitializationState
import com.google.maps.android.compose.internal.LocalGoogleMapsInitializer
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.HudPanel
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.ui.WallClock
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedMapDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun xrKeepsItsPanelsAndMeasuresOnlyTheSettledMapPose() {
        val open = mutableStateOf(false)
        val motion = mutableStateOf(MotionState.WALKING)
        var mounts = 0
        val surfaces = mutableMapOf<Int, HudSurface>()
        val mapFrames = mutableListOf<HudSurface>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SpatialHudPanels(
                dpPerMeter = 350f, scale = 1f, spec = hudVisualSpecFor(hudTestState(motion.value)),
                locationDenied = false, debug = HudDebugOptions(), motionOpacity = 1f, tracking = true,
                onNoticeTimeout = {}, bigMapOpen = open.value, onTap = {}, inputEnabled = true,
                countdown = null, quantum = HudQuantum(null, null), wallClock = WallClock { ZonedDateTime.now() },
                bigMapMode = BigMapMode.FLAT, headingTracking = null, geospatialSettings = null,
                viewport = remember { BigMapViewport() }, onMapPan = {}, onMapHold = {},
            ) { surface, _ ->
                val slot = remember { mounts++ }
                SideEffect {
                    surfaces[slot] = surface
                    if (surface.circular) mapFrames += surface
                }
                DisposableEffect(Unit) { onDispose { surfaces.remove(slot) } }
            }
        }
        fun advance(ms: Long) {
            compose.runOnUiThread { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(ms)
            compose.waitForIdle()
        }
        fun map() = surfaces.values.single { it.circular }
        fun controls() = surfaces.values.filter { it.flatCorners }
        fun buttons() = controls().filter { it.width == HudPlacement.bigMapButtons.first().width }
        advance(32)
        val mini = map()
        assertEquals(8, mounts) // Map, stats, five buttons and the outside hit panel.
        assertEquals(5, buttons().size)
        assertEquals(6, controls().count { !it.enabled })
        assertEquals(HudPanel.RIGHT, mini.measure)
        assertEquals(HudPlacement.pose(HudPanel.RIGHT.yaw, HudPanel.RIGHT.pitch), mini.pose)
        assertEquals(HudPlacement.mapSizeMeters(Layout.walkingDiameter), mini.width * mini.visualScale, 0.00001f)
        compose.runOnIdle { open.value = true }
        advance(176)
        assertTrue(mapFrames.any { it.pose == HudPlacement.pose(0f, HudPlacement.bigMapPitch) && it.alpha == 0f })
        assertEquals(mini.width, map().width, 0f)
        advance(240)
        assertEquals(HudPanel.BIGMAP, map().measure)
        assertEquals(HudPlacement.pose(0f, HudPlacement.bigMapPitch), map().pose)
        assertEquals(HudPlacement.bigMapSizeMeters, map().width * map().visualScale, 0f)
        assertTrue(controls().all { it.enabled })
        compose.runOnIdle { open.value = false }
        advance(432)
        assertTrue(buttons().all { it.alpha in 0.01f..0.99f })
        assertEquals(HudPanel.BIGMAP, map().measure)
        advance(720)
        assertEquals(mini, map())
        assertTrue(mapFrames.any { it.pose == mini.pose && it.alpha == 0f })
        assertTrue(mapFrames.all {
            it.width == mini.width && when (it.pose) {
                mini.pose -> it.visualScale == mini.visualScale
                HudPlacement.pose(0f, HudPlacement.bigMapPitch) -> it.visualScale == 1f
                else -> false
            }
        })
        compose.runOnIdle { motion.value = MotionState.STOPPED }
        advance(360)
        assertEquals(HudPlacement.mapSizeMeters(Layout.stoppedDiameter), map().width * map().visualScale, 0.00001f)
        assertEquals(mini.width, map().width, 0f)
        assertTrue(controls().none { it.enabled })
        assertEquals(8, mounts)
    }

    @Composable
    private fun SyntheticMapTiles(onCreate: () -> Unit = {}, onResize: () -> Unit = {}, content: @Composable () -> Unit) {
        val initialized = remember {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
            info.applicationInfo!!.metaData.putString("com.google.android.geo.API_KEY", context.packageName)
            assertTrue(context.hasMapsKey())
            object : GoogleMapsInitializer by DefaultGoogleMapsInitializer() {
                override val state = mutableStateOf(InitializationState.SUCCESS)
            }
        }
        // Exercise the real AndroidView factory and control tile readiness without a tile service.
        CompositionLocalProvider(LocalGoogleMapsInitializer provides initialized, LocalHudMapViewFactory provides { ctx, options ->
            onCreate()
            object : MapView(ctx, options) {
                override fun onCreate(state: Bundle?) = Unit
                override fun getMapAsync(callback: OnMapReadyCallback) = Unit
                override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
                    super.onSizeChanged(w, h, oldW, oldH)
                    onResize()
                }
            }.apply { setBackgroundColor(android.graphics.Color.GREEN) }
        }, content = content)
    }

    @Test fun startupWaitsForLoadedTilesAndThenSmoothFrames() = checkStartup(sendLoaded = true)

    @Test fun startupStopsWaitingForTilesAtTwoSecondsThenSettles() = checkStartup(sendLoaded = false)

    private fun checkStartup(sendLoaded: Boolean) {
        val startup = HudQuantumStartup()
        lateinit var root: View
        var creations = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            root = LocalView.current
            SyntheticMapTiles(onCreate = { creations++ }) {
                PhoneScreen(hudVisualSpecFor(hudTestState(MotionState.STOPPED).copy(mapCenter = MapCenter(0.1, 0.2))),
                    false, HudDebugOptions(), {}, false, quantumStartup = startup) {}
            }
        }
        val left = compose.onNodeWithTag("quantum-left", useUnmergedTree = true)
        val dots = compose.onNodeWithTag("quantum-map")
        val mini = compose.onNodeWithTag("map")
        fun tileColor(): Color {
            val rect = compose.onNodeWithTag("shared-map").fetchSemanticsNode().boundsInRoot
            return compose.runOnIdle {
                Color(root.drawToBitmap().getPixel((rect.center.x + rect.width / 4).toInt(), rect.center.y.toInt()))
            }
        }
        compose.mainClock.advanceTimeBy(16)
        left.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(144)
        left.assertExists()
        dots.assertDoesNotExist()
        mini.assertDoesNotExist()
        assertEquals(1, creations)
        assertTrue(tileColor() != Color.Green)
        if (sendLoaded) {
            compose.runOnIdle { startup.onMapLoaded() }
        } else {
            compose.mainClock.advanceTimeBy(1_824) // 1,984 ms, just before the load deadline.
            dots.assertDoesNotExist()
            mini.assertDoesNotExist()
            assertTrue(tileColor() != Color.Green)
        }
        compose.mainClock.advanceTimeBy(32)
        dots.assertDoesNotExist() // Loading has ended, but three smooth intervals have not elapsed.
        compose.mainClock.advanceTimeBy(96)
        dots.assertExists()
        compose.mainClock.advanceTimeBy(560)
        mini.assertIsDisplayed()
        dots.assertDoesNotExist()
        assertEquals(Color.Green, tileColor())
        // Late and duplicate notifications must not replay the effect.
        compose.runOnIdle { startup.onMapLoaded() }
        compose.mainClock.advanceTimeBy(160)
        dots.assertDoesNotExist()
        val logs = ShadowLog.getLogsForTag("AuraQuantum").map { it.msg }
        for (panel in listOf("Left", "MiniMap")) {
            assertEquals(1, logs.count { it.startsWith("Startup$panel settled waitMillis=") })
            assertEquals(1, logs.count { it.startsWith("Startup$panel done longestFrameMillis=") })
        }
    }

    @Test
    fun sameMapViewFadesAtFixedEndpointsForManualAndTimedCloseAndSurvivesFastMotion() {
        val map = mutableStateOf(BigMapControl())
        val motion = mutableStateOf(MotionState.STOPPED)
        var creations = 0
        var resizes = 0
        lateinit var root: View
        compose.mainClock.autoAdvance = false
        compose.setContent {
            root = LocalView.current
            SyntheticMapTiles(onCreate = { creations++ }, onResize = { resizes++ }) {
                PhoneScreen(hudVisualSpecFor(hudTestState(motion.value).copy(mapCenter = MapCenter(0.1, 0.2))),
                    false, HudDebugOptions(), {}, map.value.isOpen,
                ) { target -> map.value = map.value.tap(target, 0L, motion.value) }
            }
        }
        fun advance(ms: Long) {
            compose.runOnUiThread { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(ms)
            compose.waitForIdle()
        }
        fun bounds(): Rect = compose.onNodeWithTag("shared-map").fetchSemanticsNode().boundsInRoot
        fun tileStrength(rect: Rect) = compose.runOnIdle {
            val color = Color(root.drawToBitmap().getPixel((rect.center.x + rect.width / 4).toInt(), rect.center.y.toInt()))
            (color.green - color.red) * color.alpha
        }
        val mini = compose.onNodeWithTag("map")
        val large = compose.onNodeWithTag("bigmap")
        advance(2_800) // The synthetic surface has no tile-service callback; allow the §5 startup wait.
        val small = bounds()
        val initialLayouts = resizes
        assertEquals(1, creations)
        assertEquals(1f, tileStrength(small), 0.02f)
        repeat(2) { cycle ->
            mini.performClick()
            advance(176)
            val big = bounds()
            assertTrue(big.width > small.width && big.center.x < small.center.x)
            compose.onNodeWithTag("quantum-bigmap").assertExists()
            compose.onNodeWithTag("quantum-map").assertDoesNotExist()
            assertEquals(0f, tileStrength(big), 0.02f)
            advance(352)
            assertEquals(big, bounds())
            val fading = tileStrength(big)
            assertTrue("partly revealed map: $fading", fading in 0.02f..0.98f)
            advance(192)
            assertEquals(big, bounds())
            assertEquals(1f, tileStrength(big), 0.02f)
            if (cycle == 0) large.performTouchInput { click() }
            else compose.runOnIdle { map.value = map.value.advance(checkNotNull(map.value.closesAt), motion.value) }
            advance(176)
            assertEquals(big, bounds())
            compose.onNodeWithTag("quantum-bigmap").assertExists()
            compose.onNodeWithTag("quantum-map").assertDoesNotExist()
            assertEquals(0f, tileStrength(big), 0.02f)
            advance(500)
            assertEquals(small, bounds())
            compose.onNodeWithTag("quantum-bigmap").assertDoesNotExist()
            compose.onNodeWithTag("quantum-map").assertExists()
            assertEquals(0f, tileStrength(small), 0.02f)
            advance(560)
            assertEquals(small, bounds())
            assertEquals(1f, tileStrength(small), 0.02f)
            mini.assertIsDisplayed()
        }
        val logs = ShadowLog.getLogsForTag("AuraBigMap").map { it.msg }
        for (direction in listOf("open", "close")) {
            assertEquals(2, logs.count { it.startsWith("transition=$direction longestFrameMillis=") })
        }
        mini.performClick()
        advance(150)
        compose.runOnIdle {
            motion.value = MotionState.FAST
            map.value = map.value.advance(1L, motion.value)
        }
        advance(16)
        for (tag in listOf("map", "shared-map", "bigmap", "outside", "bigmap_mode")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.runOnIdle { motion.value = MotionState.STOPPED }
        advance(48)
        assertEquals(small, bounds())
        assertEquals(1f, tileStrength(small), 0.02f)
        assertEquals(1, creations)
        assertEquals(initialLayouts, resizes)
    }
}
