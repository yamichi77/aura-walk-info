package io.github.yamichi77.aurawalkinfo.ui.format

import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val currentTimeFormat = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)

internal fun formatCurrentTime(time: LocalTime): String = time.format(currentTimeFormat)

internal fun millisUntilNextMinute(time: ZonedDateTime): Long =
    60_000L - (time.second * 1_000L + time.nano / 1_000_000L)
