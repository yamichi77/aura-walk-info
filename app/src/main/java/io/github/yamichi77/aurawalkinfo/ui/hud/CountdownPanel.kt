package io.github.yamichi77.aurawalkinfo.ui.hud

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail

internal data class Countdown(val label: String, val seconds: Int, val phase: WalkPhase)
internal data class CountdownPresentation(
    val content: Countdown,
    val artwork: CountdownArtwork,
    val opacity: Float,
    val active: Boolean,
    val finished: Boolean,
)

@Composable
internal fun rememberCountdownPresentation(spec: HudVisualSpec): CountdownPresentation? {
    // A hidden or fast HUD removes the panel immediately, even mid-fade.
    if (spec !is HudVisualSpec.Clusters || spec.showSafetyNotice) return null
    val artwork = remember { CountdownArtwork() }
    val label = when (spec.phase) {
        is WalkPhase.Starting -> "開始"
        is WalkPhase.Ending -> "終了"
        else -> null
    }
    val countdown = label?.let { Countdown(it, spec.countdown ?: 3, spec.phase) }
    var lastCountdown by remember { mutableStateOf<Countdown?>(null) }
    if (countdown != null) SideEffect { lastCountdown = countdown }
    val finished = countdown == null && lastCountdown?.phase?.advance(SystemClock.elapsedRealtime()) == spec.phase
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = countdown != null
    val opacity by rememberTransition(visibility).animateFloat(transitionSpec = {
        if (finished) tween(COUNTDOWN_RIPPLE_MILLIS, easing = LinearEasing) else tween(200)
    }) {
        if (it) 1f else 0f
    }
    val content = countdown ?: lastCountdown?.takeIf { opacity > 0f } ?: return null
    return CountdownPresentation(content, artwork, opacity, countdown != null, finished)
}

@Composable
internal fun CountdownPanel(
    presentation: CountdownPresentation,
    dpPerMeter: Float,
    onTap: (HudTarget) -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
) {
    val digitHeight = (HudPlacement.countdownDigitHeightMeters * dpPerMeter).dp
    val artworkSize = Modifier.size(digitHeight * 1.92f)
    if (presentation.finished) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CountdownRipple(1f - presentation.opacity, artworkSize)
        }
        return
    }

    val textScale = Layout.textScale(dpPerMeter)
    val titleSize = with(LocalDensity.current) { (17 * textScale).dp.toSp().value }
    val hintSize = with(LocalDensity.current) { (13 * textScale).dp.toSp().value }
    val labelSlot = Modifier.height(digitHeight * 0.60f)
    Column(
        modifier.alpha(presentation.opacity).hudClick(HudTarget.COUNTDOWN, onTap, inputEnabled && presentation.active),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(labelSlot, contentAlignment = Alignment.Center) {
            HudLabel(presentation.content.label, titleSize, HudDetail)
        }
        val digit = presentation.content.seconds
        QuantumCountdownDigit(
            presentation.artwork, digit, checkNotNull(presentation.content.phase.deadline), presentation.active,
            digitHeight, artworkSize.semantics { contentDescription = digit.toString() },
        )
        Box(labelSlot, contentAlignment = Alignment.Center) {
            HudLabel("タップでキャンセル", hintSize, HudDetail)
        }
    }
}
