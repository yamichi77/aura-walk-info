package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlinx.coroutines.flow.first

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
internal fun rememberBigMapButtonMotion(
    quantum: QuantumAnimation?,
    open: Boolean,
    quantumEnabled: Boolean,
): BigMapButtonMotion {
    val motion = remember(open, quantumEnabled) { BigMapButtonMotion() }
    LaunchedEffect(motion) {
        if (open && quantumEnabled && quantum?.effect == QuantumEffect.OpenMap) {
            snapshotFlow { quantum.fraction }.first { it >= 1f }
            animate(0f, 1f, animationSpec = tween(COUNTDOWN_SECOND_MILLIS.toInt(), easing = LinearEasing)) { value, _ ->
                motion.orbit = value
            }
        }
    }
    LaunchedEffect(motion, motion.pinches) {
        if (motion.pinches > 0) {
            animate(0f, 1f, animationSpec = tween(QuantumTransitionMillis, easing = LinearEasing)) { value, _ ->
                motion.ripple = value
            }
        }
    }
    return motion
}

internal class BigMapButtonMotion {
    var orbit by mutableFloatStateOf(1f)
    var ripple by mutableFloatStateOf(1f)
    var pinches by mutableIntStateOf(0)
        private set
    val orbiting by derivedStateOf { orbit < 1f }
    val rippling by derivedStateOf { ripple < 1f }

    fun pinch() {
        ripple = 0f
        pinches++
    }
}

@Composable
internal fun BigMapButton(
    label: String?,
    target: HudTarget,
    opacity: Float,
    quantum: QuantumAnimation?,
    open: Boolean,
    enabled: Boolean,
    quantumEnabled: Boolean,
    diameter: Dp,
    onTap: (HudTarget) -> Unit,
    modifier: Modifier = Modifier,
    motion: BigMapButtonMotion = rememberBigMapButtonMotion(quantum, open, quantumEnabled),
) {
    val motionAlpha by animateFloatAsState(opacity, tween(HudAnimationMillis))
    val opening = quantum?.takeIf { open && it.effect == QuantumEffect.OpenMap }
    val orbiting = motion.orbiting
    val rippling = motion.rippling
    if (label == null && !rippling) return
    val click = if (label == null) Modifier else Modifier.hudClick(target, {
        if (quantumEnabled) motion.pinch()
        onTap(it)
    }, enabled)
    Column(
        modifier.graphicsLayer {
            val fade = if (quantum?.effect == QuantumEffect.CloseMap) 1f - quantum.contentAlpha else 1f
            alpha = motionAlpha * fade
        }.then(click),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val particles = if (opening != null) remember { quantumRingParticles(24) } else emptyList()
        val effectTag = when {
            opening != null -> "gather"
            rippling -> "ripple"
            orbiting -> "orbit"
            else -> null
        }
        val name = target.name.lowercase()
        Canvas(Modifier.size(diameter).testTag(
            if (effectTag == null) "$name-artwork" else "quantum-$name-$effectTag",
        )) {
            val radius = (size.minDimension - 1f) / 2
            val contentAlpha = opening?.contentAlpha ?: 1f
            if (label != null) {
                drawCircle(QuantumCyan, radius, alpha = 0.7f * contentAlpha, style = Stroke(1f))
                drawButtonIcon(label, radius, contentAlpha)
                if (orbiting) drawQuantumElectron(radius, quantumOrbitAngle(motion.orbit))
                if (opening != null) drawQuantumParticles(particles, opening, center, radius, outgoing = false)
            }
            if (rippling) drawQuantumRipple(motion.ripple, radius, radius * 1.22f)
        }
        val textSize = with(LocalDensity.current) { 14.dp.toSp() }
        // Reserve the label row while a destination pinch changes the map into the input panel.
        Text(
            label.orEmpty(), color = HudText, fontSize = textSize, lineHeight = textSize,
            letterSpacing = 0.sp, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.graphicsLayer { alpha = opening?.contentAlpha ?: 1f },
        )
    }
}

private fun DrawScope.drawButtonIcon(label: String, radius: Float, alpha: Float) {
    val extent = radius * 0.48f
    val stroke = Stroke(1.dp.toPx())
    fun point(x: Float, y: Float) = center + Offset(x * extent, y * extent)
    val outline = Path().apply {
        fun move(x: Float, y: Float) = point(x, y).let { moveTo(it.x, it.y) }
        fun line(x: Float, y: Float) = point(x, y).let { lineTo(it.x, it.y) }
        when (label) {
            "目的地" -> {
                // A map pin: a round head whose tangents meet at the tip, with a hole in the head.
                val headY = -0.35f
                val headRadius = 0.8f
                val tipY = 1.25f
                val tangent = kotlin.math.acos(headRadius / (tipY - headY))
                val tangentDegrees = Math.toDegrees(tangent.toDouble()).toFloat()
                move(0f, tipY)
                line(headRadius * kotlin.math.sin(tangent), headY + headRadius * kotlin.math.cos(tangent))
                arcTo(
                    Rect(point(-headRadius, headY - headRadius), point(headRadius, headY + headRadius)),
                    90f - tangentDegrees, 2 * tangentDegrees - 360f, false,
                )
                close()
                addOval(Rect(point(-0.3f, headY - 0.3f), point(0.3f, headY + 0.3f)))
            }
            "3D" -> {
                move(0f, -1f)
                line(1f, -0.5f)
                line(1f, 0.5f)
                line(0f, 1f)
                line(-1f, 0.5f)
                line(-1f, -0.5f)
                close()
                move(-1f, -0.5f)
                line(0f, 0f)
                line(1f, -0.5f)
                move(0f, 0f)
                line(0f, 1f)
            }
            "2D" -> addRect(Rect(point(-0.9f, -0.9f), point(0.9f, 0.9f)))
            else -> {
                move(-0.8f, -0.8f)
                line(0.8f, 0.8f)
                move(0.8f, -0.8f)
                line(-0.8f, 0.8f)
            }
        }
    }
    drawPath(outline, HudText, alpha = alpha, style = stroke)
    if (label == "目的地") {
        drawCircle(HudText, extent * 0.29f, point(0f, -0.6f), alpha, stroke)
    }
}
