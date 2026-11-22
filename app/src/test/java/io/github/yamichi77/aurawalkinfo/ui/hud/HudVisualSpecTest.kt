package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.ui.graphics.Color
import io.github.yamichi77.aurawalkinfo.location.AddressParts
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import io.github.yamichi77.aurawalkinfo.ui.format.FormattedDistance
import org.junit.Assert.assertEquals
import org.junit.Test

internal val exampleAddress = AddressState.Available(AddressParts("架空県", "みほん市", "試験町", "1丁目"))

internal fun hudTestState(motion: MotionState) = WalkSessionState(
    distanceMeters = 1_240.0,
    walkingTimeMillis = 1_122_200,
    elapsedMillis = 1_265_000,
    motionState = motion,
    address = exampleAddress,
)

class HudVisualSpecTest {
    @Test
    fun walkingUsesCompactClusters() {
        assertEquals(
            clusters(0.7f, 0xFFE0F7FF, null, "みほん市試験町1丁目", 1, 0xFFB5D4F4, 8f, 0.4f, 0xFF7FDBFF),
            hudVisualSpecFor(hudTestState(MotionState.WALKING)),
        )
    }

    @Test
    fun stoppedUsesDetailAndExpandedMap() {
        assertEquals(
            clusters(1f, 0xFFE0F7FF, "経過 21:05 ・ 15'05\"/km", "架空県みほん市\n試験町1丁目", 2,
                0xFFB5D4F4, 12f, 0.4f, 0xFF7FDBFF),
            hudVisualSpecFor(hudTestState(MotionState.STOPPED)),
        )
        val stopped = hudTestState(MotionState.STOPPED)
        assertEquals("経過 21:05", (hudVisualSpecFor(stopped.copy(distanceMeters = 99.9)) as HudVisualSpec.Clusters).detail)
        assertEquals("経過 21:05 ・ 187'02\"/km",
            (hudVisualSpecFor(stopped.copy(distanceMeters = 100.0)) as HudVisualSpec.Clusters).detail)
    }

    @Test
    fun fastShowsOnlyNotice() {
        assertEquals(HudVisualSpec.Fast, hudVisualSpecFor(hudTestState(MotionState.FAST)))
        assertEquals("高速移動中のため非表示", HudVisualSpec.Fast.message)
        assertEquals(0.85f, HudVisualSpec.Fast.opacity)
        assertEquals(14f, HudVisualSpec.Fast.textSize)
    }

    @Test
    fun noFixDimsDistanceAddressAndMap() {
        assertEquals(
            clusters(0.7f, 0xFF888780, "GPS 測位中…", "みほん市試験町1丁目", 1, 0xFF888780, 8f, 0.3f, 0xFF888780,
                mapOpacity = 0.3f),
            hudVisualSpecFor(hudTestState(MotionState.NO_FIX)),
        )
    }

    @Test
    fun mapUsesLatestCenterAndRetainsBearingAcrossLossOfFix() {
        val center = MapCenter(0.1, 0.2)
        val state = hudTestState(MotionState.NO_FIX)
        val withoutFix = hudVisualSpecFor(state) as HudVisualSpec.Clusters
        assertEquals(null, withoutFix.mapCenter)
        assertEquals(0f, withoutFix.mapBearingDeg)

        val withLastFix = hudVisualSpecFor(state.copy(mapCenter = center, mapBearingDeg = 90f)) as HudVisualSpec.Clusters
        assertEquals(center, withLastFix.mapCenter)
        assertEquals(90f, withLastFix.mapBearingDeg)
        assertEquals(0.3f, withLastFix.mapOpacity)
    }

    @Test
    fun staleAddressDimsEvenWhileStopped() {
        val state = hudTestState(MotionState.STOPPED).copy(address = exampleAddress.copy(stale = true))
        val spec = hudVisualSpecFor(state) as HudVisualSpec.Clusters
        assertEquals("架空県みほん市\n試験町1丁目", spec.address)
        assertEquals(Color(0xFF888780), spec.addressColor)
        assertEquals(Color(0xFFE0F7FF), spec.distanceColor)
    }

    private fun clusters(
        opacity: Float, distanceColor: Long, detail: String?, address: String, lines: Int, addressColor: Long,
        diameter: Float, lineOpacity: Float, arrowColor: Long, mapOpacity: Float = opacity,
    ) = HudVisualSpec.Clusters(
        opacity, FormattedDistance("1.24", "km"), Color(distanceColor), "歩行 18:42", detail, address,
        lines, Color(addressColor), diameter, lineOpacity, Color(arrowColor), mapOpacity = mapOpacity,
    )
}
