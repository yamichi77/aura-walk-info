package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.REPLAY_ACTION
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// These tests construct their own AppContainer, so skip AuraWalkApplication's startup.
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {
    @Test
    fun registersReplayReceiverOnlyWhenDebuggableAndRequiresShellPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.applicationInfo
        val originalFlags = info.flags
        try {
            info.flags = originalFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            AppContainer(app)
            assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(REPLAY_ACTION) })

            info.flags = originalFlags or ApplicationInfo.FLAG_DEBUGGABLE
            AppContainer(app)
            val receiver = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(REPLAY_ACTION) }
            assertEquals(Manifest.permission.DUMP, receiver.broadcastPermission)
        } finally {
            info.flags = originalFlags
        }
    }

    @Test
    fun subscribesToGpsOnlyAfterFineLocationPermissionAndCleansUpOnCancellation() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = shadowOf(app.getSystemService(LocationManager::class.java))
        val repository = AppContainer(app).walkSessionRepository
        val session = repository.start(backgroundScope)
        runCurrent()
        assertTrue(manager.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(manager.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, manager.getLocationRequests(LocationManager.GPS_PROVIDER).size)

        session.cancel()
        runCurrent()
        assertTrue(manager.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
    }

    @Test
    fun remembersThatSafetyNoticeWasShown() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notice = SafetyNotice(app)

        assertFalse(notice.hasBeenShown)
        notice.markShown()
        assertTrue(SafetyNotice(app).hasBeenShown)
    }
}
