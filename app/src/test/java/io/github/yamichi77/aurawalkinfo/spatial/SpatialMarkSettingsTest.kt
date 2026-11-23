package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.settings.HudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpatialMarkSettingsTest {
    @Test fun spatialSwitchPersistsIndependentlyAndSharesTheDebugNorthOverride() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = HudSettings(context)
        val state = SkyBandSettings(settings)
        assertTrue(state.spatialMarksEnabled)
        state.applyDebugIntent(Intent().putExtra("spatial_marks", false)
            .putExtra("north_offset", 32f).putExtra("north_uncertainty", 5f))
        assertFalse(state.spatialMarksEnabled)
        assertFalse(SkyBandSettings(HudSettings(context)).spatialMarksEnabled)
        assertTrue(settings.skyBand)
        assertEquals(32.0, checkNotNull(state.northOverride).offsetDegrees, 0.0)
        state.applyDebugIntent(Intent().putExtra("sky_band", false).putExtra("spatial_marks", true))
        assertTrue(settings.spatialMarks)
        assertFalse(settings.skyBand)
    }
}
