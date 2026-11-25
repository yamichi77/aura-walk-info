package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(application = AuraWalkApplication::class)
class MainActivityWalkTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun countdownPanelDisplaysAndCancelsBothPhasesAndBlocksBigMap() {
        val app = ApplicationProvider.getApplicationContext<AuraWalkApplication>()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        SafetyNotice(app).markShown()
        val intent = Intent(app, MainActivity::class.java).putExtra("startMode", "standby")
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { controller ->
            val controls = ViewModelProvider(controller.get())[HudControls::class.java]
            val left = compose.onNodeWithTag("left")
            val countdown = compose.onNodeWithTag("countdown")
            val map = compose.onNodeWithTag("map")
            fun textIn(tag: String, text: String) =
                compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true)
            fun digitIn(tag: String, digit: String) =
                compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasContentDescription(digit), useUnmergedTree = true)

            left.performClick()
            countdown.assertIsDisplayed()
            textIn("countdown", "開始").assertIsDisplayed()
            digitIn("countdown", "3").assertIsDisplayed()
            textIn("countdown", "タップでキャンセル").assertIsDisplayed()
            textIn("left", "タップで開始").assertIsDisplayed()
            digitIn("left", "3").assertDoesNotExist()
            map.performClick()
            compose.onNodeWithTag("bigmap").assertDoesNotExist()
            assertTrue(ShadowLog.getLogsForTag("AuraInput").any {
                it.msg == "CLICK target=map source=compose ignored-countdown"
            })

            val main = shadowOf(Looper.getMainLooper())
            main.idleFor(Duration.ofSeconds(1))
            digitIn("countdown", "2").assertIsDisplayed()
            main.idleFor(Duration.ofSeconds(1))
            digitIn("countdown", "1").assertIsDisplayed()
            countdown.performClick()
            countdown.assertDoesNotExist()
            assertEquals(WalkPhase.Standby, controls.phase)
            assertTrue(ShadowLog.getLogsForTag("AuraInput").any {
                it.msg == "CLICK target=countdown source=compose"
            })

            left.performClick()
            main.idleFor(Duration.ofMillis(3_050))
            assertEquals(WalkPhase.Measuring, controls.phase)
            left.performClick()
            textIn("countdown", "終了").assertIsDisplayed()
            digitIn("countdown", "3").assertIsDisplayed()
            textIn("countdown", "タップでキャンセル").assertIsDisplayed()
            textIn("left", "歩行 00:00").assertIsDisplayed()
            digitIn("left", "3").assertDoesNotExist()
            countdown.performClick()
            assertEquals(WalkPhase.Measuring, controls.phase)
            left.performClick()
            countdown.assertIsDisplayed()
            left.performClick()
            countdown.assertDoesNotExist()
            assertEquals(WalkPhase.Measuring, controls.phase)
        }
    }

    @Test
    fun debugReceiverRequiresDumpAndSharesTheCountdownAndNoticePath() {
        val app = ApplicationProvider.getApplicationContext<AuraWalkApplication>()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val intent = Intent(app, MainActivity::class.java).putExtra("startMode", "standby")
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            val controls = ViewModelProvider(activity)[HudControls::class.java]
            val receiver = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(DEBUG_TAP_ACTION) }
            assertEquals(Manifest.permission.DUMP, receiver.broadcastPermission)
            assertEquals(WalkPhase.Standby, controls.phase)
            val left = Intent(DEBUG_TAP_ACTION).putExtra("target", "left")
            receiver.broadcastReceiver.onReceive(activity, left)
            assertTrue(SafetyNotice(activity).hasBeenShown)
            assertEquals(WalkPhase.Standby, controls.phase)
            receiver.broadcastReceiver.onReceive(activity, left)
            assertTrue(controls.phase is WalkPhase.Starting)
            val countdown = Intent(DEBUG_TAP_ACTION).putExtra("target", "countdown")
            receiver.broadcastReceiver.onReceive(activity, countdown)
            assertEquals(WalkPhase.Standby, controls.phase)
            receiver.broadcastReceiver.onReceive(activity, left)
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            main.idleFor(Duration.ofMillis(3_050))
            assertEquals(WalkPhase.Measuring, controls.phase)
        }
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(DEBUG_TAP_ACTION) })
    }

    @Test
    fun debugAutoStartsInMeasuringAndReleaseIgnoresTheExtra() {
        val app = ApplicationProvider.getApplicationContext<AuraWalkApplication>()
        val intent = Intent(app, MainActivity::class.java).putExtra("startMode", "auto")
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use {
            assertEquals(WalkPhase.Measuring, ViewModelProvider(it.get())[HudControls::class.java].phase)
        }
        app.applicationInfo.flags = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use {
            assertEquals(WalkPhase.Standby, ViewModelProvider(it.get())[HudControls::class.java].phase)
            assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(DEBUG_TAP_ACTION) })
        }
    }

    @Test
    fun bigMapSwitchRenewsItsDeadlineAndPersistsAcrossReopenAndNewActivity() {
        val app = ApplicationProvider.getApplicationContext<AuraWalkApplication>()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val intent = Intent(app, MainActivity::class.java).putExtra("fakeState", "stopped")
        val main = shadowOf(Looper.getMainLooper())
        val mini = compose.onNodeWithTag("map")
        val big = compose.onNodeWithTag("bigmap")
        val toggle = compose.onNodeWithTag("bigmap_mode")
        val show3d = compose.onNode(hasTestTag("bigmap_mode") and hasText("3D"))
        val show2d = compose.onNode(hasTestTag("bigmap_mode") and hasText("2D"))
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { controller ->
            val controls = ViewModelProvider(controller.get())[HudControls::class.java]
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2_200) // No tile-service callback in this activity test.
            mini.assertIsDisplayed().performClick()
            show3d.assertIsDisplayed()
            val deadline = controls.map.closesAt
            main.idleFor(Duration.ofSeconds(2))
            toggle.performTouchInput { click() }
            show2d.assertIsDisplayed()
            big.assertIsDisplayed()
            assertTrue(checkNotNull(controls.map.closesAt) >= checkNotNull(deadline) + 2_000L)
            compose.onNodeWithTag("quantum-bigmap").assertDoesNotExist()
            big.performTouchInput { click() }
            big.assertDoesNotExist()
            mini.assertIsDisplayed().performClick()
            big.assertIsDisplayed()
            compose.onNodeWithTag("outside").performTouchInput { click(Offset(5f, height - 5f)) }
            toggle.assertDoesNotExist()
            mini.assertIsDisplayed().performClick()
            show2d.assertIsDisplayed()
            main.idleFor(Duration.ofSeconds(30))
            show2d.assertDoesNotExist()
            mini.assertIsDisplayed()
        }
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2_200)
            mini.assertIsDisplayed()
            toggle.assertDoesNotExist()
            mini.performClick()
            show2d.assertIsDisplayed()
            toggle.performTouchInput { click() }
            show3d.assertIsDisplayed()
        }
    }
}
