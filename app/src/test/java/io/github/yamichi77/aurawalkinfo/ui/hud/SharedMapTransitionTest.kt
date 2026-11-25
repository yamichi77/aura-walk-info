package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedMapTransitionTest {
    @Test
    fun fixedRasterShowsTheOriginalGroundSpanAtBothMiniSizes() {
        assertEquals(18.5f, compensatedMapZoom(17.5f, 0.5f), 0f)
        for (diameter in listOf(Layout.walkingDiameter, Layout.stoppedDiameter)) {
            val mini = HudPlacement.mapSizeMeters(diameter)
            val large = HudPlacement.bigMapSizeMeters
            val zoom = compensatedMapZoom(MiniMapZoom, mini / large)
            assertEquals(mini.toDouble(), large / Math.pow(2.0, (zoom - MiniMapZoom).toDouble()), 0.00001)
        }
    }
}
