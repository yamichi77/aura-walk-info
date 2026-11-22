package io.github.yamichi77.aurawalkinfo.ui.format

import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.location.JapaneseAddressFormatter
import java.util.Locale

data class FormattedDistance(val number: String, val unit: String)

fun formatDistance(meters: Double): FormattedDistance {
    val wholeMeters = meters.toLong()
    return when {
        wholeMeters < 1_000 -> FormattedDistance(wholeMeters.toString(), "m")
        wholeMeters < 10_000 -> FormattedDistance(
            "%d.%02d".format(Locale.ROOT, wholeMeters / 1_000, wholeMeters % 1_000 / 10),
            "km",
        )
        else -> FormattedDistance(
            "%d.%d".format(Locale.ROOT, wholeMeters / 1_000, wholeMeters % 1_000 / 100),
            "km",
        )
    }
}

fun formatWalkingTime(millis: Long): String = "歩行 ${formatDuration(millis)}"

fun formatElapsedTime(millis: Long): String = "経過 ${formatDuration(millis)}"

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val remainder = seconds % 60
    return if (hours == 0L) "%02d:%02d".format(Locale.ROOT, minutes, remainder)
    else "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainder)
}

fun formatAveragePace(walkingTimeMillis: Long, distanceMeters: Double): String? {
    if (distanceMeters < 100) return null
    val secondsPerKm = (walkingTimeMillis / distanceMeters).toLong()
    return "%d'%02d\"/km".format(Locale.ROOT, secondsPerKm / 60, secondsPerKm % 60)
}

fun formatAddress(address: AddressState, multiline: Boolean): String = when (address) {
    AddressState.Loading -> "住所取得中…"
    AddressState.Failed -> "—"
    is AddressState.Available -> {
        val text = if (multiline) JapaneseAddressFormatter.twoLines(address.parts).joinToString("\n")
        else JapaneseAddressFormatter.oneLine(address.parts)
        text.ifEmpty { "—" }
    }
}
