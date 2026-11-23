package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(application = AuraWalkApplication::class)
class MainActivityWalkTest {
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
}
