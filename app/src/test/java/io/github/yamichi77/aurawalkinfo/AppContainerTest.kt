package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {
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
