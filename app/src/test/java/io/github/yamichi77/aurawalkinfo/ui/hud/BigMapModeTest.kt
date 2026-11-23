package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.settings.HudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BigMapModeTest {
    @Test
    fun cameraKeepsTheFixAsTargetAndNorthUpInBothModes() {
        val center = MapCenter(0.1, 0.2)
        val flat = BigMapMode.FLAT.cameraPosition(center)
        val tilted = BigMapMode.TILTED.cameraPosition(center)
        assertEquals(16.5f, flat.zoom, 0f)
        assertEquals(0f, flat.tilt, 0f)
        assertEquals(17.5f, tilted.zoom, 0f)
        assertEquals(60f, tilted.tilt, 0f)
        for (position in listOf(flat, tilted)) {
            assertEquals(0f, position.bearing, 0f)
            assertEquals(center.latitude, position.target.latitude, 0.0)
            assertEquals(center.longitude, position.target.longitude, 0.0)
        }
        assertFalse(BigMapMode.FLAT.buildingsEnabled)
        assertTrue(BigMapMode.TILTED.buildingsEnabled)
        assertEquals(0f, northUpCameraPosition(center, 17.5f).tilt, 0f)
    }

    @Test
    fun defaultsToFlatAndReloadsBothSavedChoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = HudSettings(context)
        assertFalse(settings.bigMap3d)
        settings.bigMap3d = true
        assertTrue(HudSettings(context).bigMap3d)
        settings.bigMap3d = false
        assertFalse(HudSettings(context).bigMap3d)
    }
}
