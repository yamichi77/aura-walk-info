package io.github.yamichi77.aurawalkinfo.nav

internal fun decodePolyline(encoded: String): List<NavPoint> = buildList {
    var index = 0
    var latitude = 0L
    var longitude = 0L

    fun decodeDelta(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            require(index < encoded.length && shift <= 30)
            val chunk = encoded[index++].code - 63
            require(chunk in 0..63)
            value = value or ((chunk and 31).toLong() shl shift)
            if (chunk < 32) return if (value and 1L == 0L) value shr 1 else (value shr 1).inv()
            shift += 5
        }
    }

    while (index < encoded.length) {
        latitude += decodeDelta()
        longitude += decodeDelta()
        add(NavPoint(latitude / 100_000.0, longitude / 100_000.0))
    }
}
