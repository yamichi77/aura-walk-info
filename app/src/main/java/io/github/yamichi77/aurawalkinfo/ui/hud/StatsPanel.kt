package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal const val HudAnimationMillis = 300

@Composable
internal fun StatsPanel(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    textScale: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopStart) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        when {
            spec is HudVisualSpec.Fast -> HudLabel(
                spec.message, spec.textSize * textScale, HudText, Modifier.alpha(spec.opacity),
                widthPx = widthPx,
            )
            locationDenied -> HudLabel("位置の権限がありません", 14 * textScale, HudText, widthPx = widthPx)
            spec is HudVisualSpec.Clusters -> {
                val opacity by animateFloatAsState(spec.opacity, tween(HudAnimationMillis))
                Column(
                    Modifier.alpha(opacity),
                    // Japanese fallback fonts reserve extra interline space even for single-line text.
                    verticalArrangement = Arrangement.spacedBy((-4 * textScale).dp),
                ) {
                    Row {
                        HudLabel(
                            spec.distance.number, 32 * textScale, spec.distanceColor,
                            Modifier.alignByBaseline(), weight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width((3 * textScale).dp))
                        HudLabel(
                            spec.distance.unit, 16 * textScale, spec.distanceColor,
                            Modifier.alignByBaseline(), weight = FontWeight.Medium,
                        )
                    }
                    HudLabel(spec.walkingTime, 16 * textScale, HudText)
                    spec.detail?.let { HudLabel(it, 14 * textScale, HudDetail, widthPx = widthPx) }
                    spec.address.lineSequence().take(spec.addressLines).forEach {
                        HudLabel(it, 14 * textScale, spec.addressColor, widthPx = widthPx)
                    }
                }
            }
        }
    }
}

@Composable
private fun HudLabel(
    text: String,
    size: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    widthPx: Float = Float.POSITIVE_INFINITY,
) {
    val style = TextStyle(
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.06f).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    // Keep the specified line count and vertical text size inside the angular bounds.
    val scaleX = if (widthPx.isFinite()) {
        val naturalWidth = rememberTextMeasurer().measure(text, style, maxLines = 1).size.width
        if (naturalWidth > widthPx) ((widthPx - 1) / naturalWidth).coerceIn(0.5f, 1f) else 1f
    } else {
        1f
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style.copy(textGeometricTransform = TextGeometricTransform(scaleX = scaleX)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
