package io.github.yamichi77.aurawalkinfo.ui.format

import io.github.yamichi77.aurawalkinfo.location.AddressParts
import io.github.yamichi77.aurawalkinfo.location.AddressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkFormattingTest {
    @Test
    fun formatsDistanceWithoutRoundingUp() {
        assertEquals(FormattedDistance("850", "m"), formatDistance(850.0))
        assertEquals(FormattedDistance("999", "m"), formatDistance(999.6))
        assertEquals(FormattedDistance("1.00", "km"), formatDistance(1_000.0))
        assertEquals(FormattedDistance("1.24", "km"), formatDistance(1_249.9))
        assertEquals(FormattedDistance("12.3", "km"), formatDistance(12_399.9))
    }

    @Test
    fun formatsWalkTimeElapsedTimeAndPace() {
        assertEquals("歩行 18:42", formatWalkingTime(1_122_000))
        assertEquals("歩行 1:00:00", formatWalkingTime(3_600_000))
        assertEquals("経過 1:02:03", formatElapsedTime(3_723_000))
        assertEquals("15'05\"/km", formatAveragePace(905_000, 1_000.0))
        assertEquals("15'05\"/km", formatAveragePace(90_500, 100.0))
        assertNull(formatAveragePace(90_000, 99.9))
    }

    @Test
    fun formatsAddressStatesAndLeavesStaleStylingToCaller() {
        assertEquals("住所取得中…", formatAddress(AddressState.Loading, multiline = false))
        assertEquals("—", formatAddress(AddressState.Failed, multiline = true))
        val address = AddressState.Available(AddressParts("東京都", "架空区", "試験町"), stale = true)
        assertEquals("架空区試験町", formatAddress(address, multiline = false))
        assertEquals("東京都架空区\n試験町", formatAddress(address, multiline = true))
    }
}
