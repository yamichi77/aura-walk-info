package io.github.yamichi77.aurawalkinfo.spatial

import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import kotlin.math.max

internal const val SIGNAL_HEIGHT_METERS = 4.0
internal const val SIGNAL_WIDTH_METERS = 0.64
internal const val SIGNAL_ROW_HEIGHT_METERS = 0.3
internal const val SIGNAL_TEXT_SIZE_METERS = 0.26 // About 1 degree at 15 m.
internal const val SIGNAL_DOT_DIAMETER_METERS = 0.10
internal const val SIGNAL_FOOTER_HEIGHT_METERS = 0.16
internal const val SIGNAL_REFERENCE = "参考表示"
private const val STALE_COLOR = 0xFF888780.toInt()
private const val TEXT_COLOR = 0xFFE0F7FF.toInt()
private const val RED_COLOR = 0xFFE07A7A.toInt()
private const val GREEN_COLOR = 0xFF7FBFA8.toInt()
private const val FILL_OPACITY = 0.3f

internal data class SignalRow(
    val number: Int,
    val aspect: PicsAspect,
    val label: String,
    val segments: Int,
    val color: Int,
    val outlineOpacity: Float,
    val fillOpacity: Float,
)

internal data class SignalTotem(val freshness: PicsFreshness, val rows: List<SignalRow>) {
    val textColor get() = if (freshness == PicsFreshness.STALE) STALE_COLOR else TEXT_COLOR
    val heightMeters get() = rows.size * SIGNAL_ROW_HEIGHT_METERS + SIGNAL_FOOTER_HEIGHT_METERS
}

internal fun PicsCandidate.toSignalTotem() = SignalTotem(freshness, lights.mapIndexed { index, light ->
    val label = when (light.aspect) {
        PicsAspect.RED -> "赤"
        PicsAspect.GREEN -> "青"
        PicsAspect.FLASHING_GREEN -> "青点滅"
        else -> "—"
    }
    val color = when {
        freshness == PicsFreshness.STALE -> STALE_COLOR
        light.aspect == PicsAspect.RED -> RED_COLOR
        light.aspect == PicsAspect.GREEN || light.aspect == PicsAspect.FLASHING_GREEN -> GREEN_COLOR
        else -> STALE_COLOR
    }
    // Match the red and green dots' sRGB luma over a transparent/black background.
    val opacity = if (color == GREEN_COLOR) signalLuma(RED_COLOR) / signalLuma(GREEN_COLOR) else 1f
    SignalRow(
        index + 1, light.aspect, label, light.remaining?.takeIf { it in 1..8 } ?: 0, color, opacity,
        if (light.aspect == PicsAspect.FLASHING_GREEN) 0f else opacity * FILL_OPACITY,
    )
})

private fun signalLuma(color: Int): Float =
    0.2126f * (color shr 16 and 0xff) + 0.7152f * (color shr 8 and 0xff) + 0.0722f * (color and 0xff)

internal data class SignalPlacement(val center: MarkVector, val towardEye: MarkVector)

internal fun placeSignalTotem(
    center: MarkVector,
    eye: MarkVector,
    up: MarkVector,
    heightMeters: Double,
): SignalPlacement {
    val toEye = eye - center
    val heightToEye = toEye.dot(up)
    val lift = max(0.0, heightToEye + heightMeters / 2)
    return SignalPlacement(center + up * lift, toEye - up * heightToEye)
}

internal fun MarkFade.signalOpacity(reason: MarkReason, now: Long): Float =
    update(show = reason == MarkReason.SHOW, immediate = reason != MarkReason.SHOW, now = now)
