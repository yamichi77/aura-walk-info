package io.github.yamichi77.aurawalkinfo.location

object JapaneseAddressFormatter {
    fun oneLine(parts: AddressParts): String {
        val text = join(parts.city, parts.town, parts.chome)
        val count = text.codePointCount(0, text.length)
        return if (count <= 10) text else text.substring(0, text.offsetByCodePoints(0, 9)) + "…"
    }

    fun twoLines(parts: AddressParts): List<String> =
        listOf(join(parts.prefecture, parts.city), join(parts.town, parts.chome))
            .filter(String::isNotEmpty)

    private fun join(vararg parts: String?): String =
        parts.filterNotNull().joinToString("") { it.trim() }.withHalfWidthDigits()
}

internal fun String.withHalfWidthDigits(): String = map {
    if (it in '０'..'９') '0' + (it - '０') else it
}.joinToString("")
