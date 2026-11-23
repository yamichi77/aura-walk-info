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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
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
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.theme.HudAddress
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlinx.coroutines.delay

internal const val HudAnimationMillis = 300

@Composable
internal fun StatsPanel(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    textScale: Float,
    modifier: Modifier = Modifier,
    onNoticeTimeout: () -> Unit = {},
    quantum: QuantumAnimation? = null,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopStart) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        when {
            spec is HudVisualSpec.Fast -> HudLabel(
                spec.message, spec.textSize * textScale, HudText, Modifier.alpha(spec.opacity),
                widthPx = widthPx,
            )
            spec is HudVisualSpec.Hidden -> Unit
            spec is HudVisualSpec.Clusters && spec.showSafetyNotice -> {
                val onTimeout by rememberUpdatedState(onNoticeTimeout)
                LaunchedEffect(Unit) {
                    delay(15_000L)
                    onTimeout()
                }
                Text(
                    text = SafetyNotice.MESSAGE,
                    color = HudText,
                    fontSize = (14 * textScale).sp,
                    lineHeight = (16 * textScale).sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                )
            }
            locationDenied -> HudLabel("位置の権限がありません", 14 * textScale, HudText, widthPx = widthPx)
            spec is HudVisualSpec.Clusters -> {
                val opacity by animateFloatAsState(spec.opacity, tween(HudAnimationMillis))
                val startup = quantum?.takeIf { it.effect == QuantumEffect.Startup }
                val effectTag = if (quantum?.drawing == true) Modifier.testTag("quantum-left") else Modifier
                Column(
                    Modifier.alpha(opacity).then(effectTag),
                    // Japanese fallback fonts reserve extra interline space even for single-line text.
                    verticalArrangement = Arrangement.spacedBy((-4 * textScale).dp),
                ) {
                    if (spec.leftPhase == WalkPhase.Standby) {
                        HudLabel("タップで開始", 16 * textScale, HudText, widthPx = widthPx,
                            quantum = startup, particles = 20)
                        HudLabel(spec.address, 14 * textScale, HudAddress, widthPx = widthPx,
                            quantum = startup, particles = 12)
                        return@Column
                    }
                    val summary = spec.leftPhase == WalkPhase.Summary
                    if (summary) HudLabel("おつかれさま", 14 * textScale, HudDetail, widthPx = widthPx,
                        quantum = quantum, particles = 10)
                    Row {
                        HudLabel(
                            spec.distance.number, 32 * textScale, spec.distanceColor,
                            Modifier.alignByBaseline(), weight = FontWeight.Medium,
                            quantum = quantum, particles = 18,
                        )
                        Spacer(Modifier.width((3 * textScale).dp))
                        HudLabel(
                            spec.distance.unit, 16 * textScale, spec.distanceColor,
                            Modifier.alignByBaseline(), weight = FontWeight.Medium,
                            quantum = quantum, particles = 6,
                        )
                    }
                    HudLabel(spec.walkingTime, 16 * textScale, HudText, quantum = quantum, particles = 12)
                    spec.detail?.let {
                        HudLabel(it, 14 * textScale, HudDetail, widthPx = widthPx,
                            quantum = if (summary) quantum else startup, particles = 8)
                    }
                    if (summary) {
                        val hint = if (quantum == null) Modifier else Modifier.graphicsLayer { alpha = quantum.contentAlpha }
                        HudLabel("タップで新しく開始", 14 * textScale, HudDetail, hint, widthPx = widthPx)
                    } else {
                        spec.address.lineSequence().take(spec.addressLines).forEach {
                            HudLabel(it, 14 * textScale, spec.addressColor, widthPx = widthPx,
                                quantum = startup, particles = 6)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HudLabel(
    text: String,
    size: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    widthPx: Float = Float.POSITIVE_INFINITY,
    quantum: QuantumAnimation? = null,
    particles: Int = 0,
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
    val baseline = if (quantum != null) remember { mutableFloatStateOf(0f) } else null
    val reveal = if (quantum != null && baseline != null) {
        quantumLabelModifier(quantum, text, size, weight, scaleX, particles) { baseline.floatValue }
    } else Modifier
    Text(
        text = text,
        modifier = modifier.then(reveal),
        onTextLayout = { if (baseline != null) baseline.floatValue = it.firstBaseline },
        color = color,
        style = style.copy(textGeometricTransform = TextGeometricTransform(scaleX = scaleX)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
