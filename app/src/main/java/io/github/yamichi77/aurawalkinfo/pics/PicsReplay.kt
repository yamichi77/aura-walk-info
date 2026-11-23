package io.github.yamichi77.aurawalkinfo.pics

import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull

internal enum class PicsReplay(val command: String, private val distanceMeters: Double) {
    RED_TO_GREEN("red_to_green", 10.0),
    STALE("stale", 10.0),
    FAR("far", 40.0),
    SPATIAL("spatial", 25.0);

    suspend fun play(states: Flow<WalkSessionState>, receive: (ByteArray) -> Unit) {
        val origin = states.mapNotNull { it.mapCenter }.firstOrNull() ?: return
        val delta = Math.toDegrees(distanceMeters / EARTH_RADIUS_METERS)
        val latitude = if (origin.latitude + delta <= 90.0) origin.latitude + delta else origin.latitude - delta
        receive(positionPacket(MapCenter(latitude, origin.longitude)))
        if (this == SPATIAL) {
            // Keep the intersection within the forward field of view long enough to observe.
            repeat(12) { index ->
                if (index > 0) delay(600L)
                receive(packet(2, 2).put(0x21.toByte()).put(0x73.toByte()).array())
            }
            return
        }
        val signals = when (this) {
            RED_TO_GREEN -> listOf(0x21, 0x11, 0x01, 0x02, 0x73)
            else -> listOf(0x21)
        }
        signals.forEachIndexed { index, signal ->
            if (index > 0) delay(600L)
            receive(packet(2, 1).put(signal.toByte()).array())
        }
    }

    private fun positionPacket(point: MapCenter) = packet(1, 8)
        .putInt((point.latitude * 1_000_000).roundToInt())
        .putInt((point.longitude * 1_000_000).roundToInt())
        .array()

    private fun packet(type: Int, payloadSize: Int) = ByteBuffer.allocate(10 + payloadSize)
        .put(2, type.toByte())
        .putInt(6, 0xDEADBEEF.toInt())
        .apply { position(10) }
}
