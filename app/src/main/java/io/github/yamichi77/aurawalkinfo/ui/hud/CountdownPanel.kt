package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal data class Countdown(val label: String, val seconds: Int)
internal data class CountdownPresentation(val content: Countdown, val opacity: Float, val active: Boolean)

@Composable
internal fun rememberCountdownPresentation(spec: HudVisualSpec): CountdownPresentation? {
    // A hidden or fast HUD removes the panel immediately, even mid-fade.
    if (spec !is HudVisualSpec.Clusters || spec.showSafetyNotice) return null
    val label = when (spec.phase) {
        is WalkPhase.Starting -> "開始"
        is WalkPhase.Ending -> "終了"
        else -> null
    }
    val countdown = label?.let { Countdown(it, spec.countdown ?: 3) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = countdown != null
    val opacity by rememberTransition(visibility).animateFloat(transitionSpec = { tween(200) }) {
        if (it) 1f else 0f
    }
    var lastCountdown by remember { mutableStateOf<Countdown?>(null) }
    if (countdown != null) SideEffect { lastCountdown = countdown }
    val content = countdown ?: lastCountdown?.takeIf { opacity > 0f } ?: return null
    return CountdownPresentation(content, opacity, countdown != null)
}

@Composable
internal fun CountdownPanel(
    presentation: CountdownPresentation,
    dpPerMeter: Float,
    onTap: (HudTarget) -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
) {
    // The default sans-serif digit occupies approximately 72% of its em.
    val digitSize = HudPlacement.countdownDigitHeightMeters * dpPerMeter /
        (0.72f * LocalDensity.current.fontScale)
    Column(
        modifier.alpha(presentation.opacity).hudClick(HudTarget.COUNTDOWN, onTap, inputEnabled && presentation.active),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HudLabel(presentation.content.label, digitSize / 3, HudDetail)
        HudLabel(presentation.content.seconds.toString(), digitSize, HudText, weight = FontWeight.Medium)
        HudLabel("タップでキャンセル", digitSize / 4, HudDetail)
    }
}
