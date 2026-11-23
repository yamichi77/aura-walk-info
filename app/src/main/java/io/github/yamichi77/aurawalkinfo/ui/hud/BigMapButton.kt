package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

@Composable
internal fun rememberBigMapDestinationLabel(spec: HudVisualSpec.Clusters, open: Boolean): String? {
    val label = when (spec.destinationPrompt) {
        DestinationPrompt.Map -> if (spec.navigation == null) "目的地" else "ナビ終了"
        DestinationPrompt.ConfirmEnd -> if (spec.navigation == null) "目的地" else "もう一度でナビ終了"
        else -> null
    }
    // Closing the map resets the prompt. Keep the outgoing label for the transition.
    var outgoingLabel by remember { mutableStateOf(label) }
    if (open) SideEffect { outgoingLabel = label }
    return if (open) label else outgoingLabel
}

@Composable
internal fun BigMapButton(
    label: String,
    target: HudTarget,
    opacity: Float,
    quantum: QuantumAnimation?,
    enabled: Boolean,
    onTap: (HudTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionAlpha by animateFloatAsState(opacity, tween(HudAnimationMillis))
    Box(
        modifier.graphicsLayer {
            val transitionAlpha = when (quantum?.effect) {
                QuantumEffect.OpenMap -> quantum.contentAlpha
                QuantumEffect.CloseMap -> 1f - quantum.contentAlpha
                else -> 1f
            }
            alpha = motionAlpha * transitionAlpha
        }.hudClick(target, onTap, enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding(2.dp)
                .border(Dp.Hairline, HudText.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val textSize = with(LocalDensity.current) { 14.dp.toSp() }
            Text(
                label, color = HudText, fontSize = textSize, lineHeight = textSize,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center, maxLines = 2,
            )
        }
    }
}
