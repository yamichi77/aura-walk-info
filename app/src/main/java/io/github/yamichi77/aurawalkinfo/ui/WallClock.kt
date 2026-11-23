package io.github.yamichi77.aurawalkinfo.ui

import java.time.ZonedDateTime

internal fun interface WallClock {
    fun now(): ZonedDateTime
}
