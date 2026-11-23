package io.github.yamichi77.aurawalkinfo.pics

import io.github.yamichi77.aurawalkinfo.session.MapCenter
import java.nio.ByteBuffer

internal const val PICS_COMPANY_ID = 0x01CE
internal const val TEST_COMPANY_ID = 0xFFFF

internal sealed interface PicsPacket {
    val intersectionId: String

    data class Identifier(override val intersectionId: String, val text: String) : PicsPacket
    data class Position(override val intersectionId: String, val point: MapCenter) : PicsPacket
    data class Signal(override val intersectionId: String, val lights: List<PicsLight>) : PicsPacket
}

internal enum class PicsAspect { NONE, RED, FLASHING_GREEN, GREEN, OUT_OF_CONTROL, UNKNOWN }

internal data class PicsLight(val aspect: PicsAspect, val remaining: Int?)

internal class PicsParser(debuggable: Boolean) {
    val companyIds = if (debuggable) listOf(PICS_COMPANY_ID, TEST_COMPANY_ID) else listOf(PICS_COMPANY_ID)

    fun parse(companyId: Int, data: ByteArray): PicsPacket? {
        if (companyId !in companyIds || data.size < 11) return null
        val bytes = ByteBuffer.wrap(data)
        val id = bytes.getInt(6).toUInt().toString(16).padStart(8, '0').uppercase()
        return when (data[2].toInt()) {
            0 -> PicsPacket.Identifier(id, data.decodeToString(startIndex = 10).trimEnd('\u0000'))
            1 -> {
                if (data.size < 18) return null
                val latitude = bytes.getInt(10) / 1_000_000.0
                val longitude = bytes.getInt(14) / 1_000_000.0
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
                PicsPacket.Position(id, MapCenter(latitude, longitude))
            }
            2 -> PicsPacket.Signal(id, (10 until minOf(data.size, 26)).map { offset ->
                val value = data[offset].toInt() and 0xFF
                val aspect = when (value and 0x0F) {
                    0 -> PicsAspect.NONE
                    1 -> PicsAspect.RED
                    2 -> PicsAspect.FLASHING_GREEN
                    3 -> PicsAspect.GREEN
                    4 -> PicsAspect.OUT_OF_CONTROL
                    else -> PicsAspect.UNKNOWN
                }
                val remaining = (value ushr 4).takeIf { it < 8 }?.plus(1)
                PicsLight(aspect, remaining)
            })
            else -> null
        }
    }
}
