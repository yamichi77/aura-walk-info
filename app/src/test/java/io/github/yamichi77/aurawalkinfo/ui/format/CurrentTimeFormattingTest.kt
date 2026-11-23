package io.github.yamichi77.aurawalkinfo.ui.format

import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentTimeFormattingTest {
    @Test
    fun usesTwentyFourHourTimeWithoutLeadingHourZeroOrSeconds() {
        assertEquals("9:05", formatCurrentTime(LocalTime.of(9, 5, 42)))
        assertEquals("14:32", formatCurrentTime(LocalTime.of(14, 32)))
        assertEquals("0:00", formatCurrentTime(LocalTime.MIDNIGHT))
        assertEquals("23:59", formatCurrentTime(LocalTime.of(23, 59)))
    }

    @Test
    fun waitsUntilTheNextMinuteRoundingUpToWholeMilliseconds() {
        val midnight = ZonedDateTime.parse("2026-11-23T00:00:00+09:00")
        assertEquals(60_000L, millisUntilNextMinute(midnight))
        assertEquals(17_750L, millisUntilNextMinute(midnight.plusSeconds(42).plusNanos(250_000_001)))
        assertEquals(1L, millisUntilNextMinute(midnight.minusNanos(1)))
    }
}
