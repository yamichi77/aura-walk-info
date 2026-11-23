package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal const val QuantumEffectsEnabled = true
internal const val QuantumTransitionMillis = 500
private const val FadeStart = 0.7f

internal enum class QuantumEffect { Startup, Walk, OpenMap, CloseMap }

internal class QuantumAnimation(val effect: QuantumEffect, private val progress: State<Float>) {
    val fraction: Float get() = progress.value
    val contentAlpha: Float get() = quantumContentAlpha(fraction)
    val drawing by derivedStateOf { fraction >= 0f }
}

internal fun quantumContentAlpha(fraction: Float) =
    ((fraction - FadeStart) / (1f - FadeStart)).coerceIn(0f, 1f)

internal fun quantumTransitionFrame(fraction: Float, outgoing: Boolean) = QuantumParticleFrame(
    convergence = if (outgoing) 1f else quantumConvergence(fraction / FadeStart),
    dispersal = if (outgoing) 0.30f * quantumConvergence(fraction) else 0f,
    jitterPhase = fraction * QuantumTransitionMillis * 0.024f,
)

internal data class HudQuantum(
    val left: QuantumAnimation?,
    val map: QuantumAnimation?,
) {
    val miniMap: QuantumAnimation? get() = map?.takeUnless { it.effect == QuantumEffect.OpenMap }
    val bigMap: QuantumAnimation? get() = map?.takeUnless { it.effect == QuantumEffect.Startup }
}

internal class HudQuantumStartup {
    var leftPlayed by mutableStateOf(false)
    var mapPlayed by mutableStateOf(false)
}

private class QuantumHistory {
    var phase: WalkPhase? = null
    var mapOpen: Boolean? = null
    var left: QuantumEffect? = null
    var map: QuantumEffect? = null
}

@Composable
internal fun rememberHudQuantum(
    spec: HudVisualSpec,
    locationDenied: Boolean,
    showBigMap: Boolean,
    countdown: CountdownPresentation?,
    startup: HudQuantumStartup,
    ready: Boolean = true,
    enabled: Boolean = QuantumEffectsEnabled,
): HudQuantum {
    val history = remember { QuantumHistory() }
    val clusters = spec as? HudVisualSpec.Clusters
    val visible = ready && enabled && clusters != null && !locationDenied
    val phase = clusters?.phase.takeIf { visible && clusters?.showSafetyNotice == false }
    val mapOpen = showBigMap.takeIf { visible }
    val startLeft = phase != null && !startup.leftPlayed
    val startMap = mapOpen != null && !startup.mapPlayed
    val left = when {
        startLeft -> QuantumEffect.Startup
        phase == null -> null
        phase == history.phase -> history.left
        history.phase is WalkPhase.Starting && phase == WalkPhase.Measuring -> QuantumEffect.Walk
        history.phase is WalkPhase.Ending && phase == WalkPhase.Summary -> QuantumEffect.Walk
        else -> null
    }
    val map = when {
        mapOpen == null -> null
        startMap && !mapOpen -> QuantumEffect.Startup
        mapOpen == history.mapOpen -> history.map
        history.mapOpen == null -> null
        mapOpen -> QuantumEffect.OpenMap
        else -> QuantumEffect.CloseMap
    }
    SideEffect {
        if (startLeft) startup.leftPlayed = true
        if (startMap) startup.mapPlayed = true
        history.phase = phase
        history.mapOpen = mapOpen
        history.left = left
        history.map = map
    }
    return HudQuantum(
        rememberQuantumAnimation(left, left == QuantumEffect.Walk && countdown?.finished == true),
        rememberQuantumAnimation(map),
    )
}

@Composable
private fun rememberQuantumAnimation(effect: QuantumEffect?, waitForRipple: Boolean = false): QuantumAnimation? {
    if (effect == null) return null
    val progress = remember(effect) { mutableFloatStateOf(if (waitForRipple) -1f else 0f) }
    var finished by remember(effect) { mutableStateOf(false) }
    LaunchedEffect(effect, waitForRipple) {
        if (!waitForRipple && !finished) {
            animate(0f, 1f, animationSpec = tween(QuantumTransitionMillis, easing = LinearEasing)) { value, _ ->
                progress.floatValue = value
            }
            finished = true
        }
    }
    return if (finished) null else remember(effect) { QuantumAnimation(effect, progress) }
}

@Composable
internal fun QuantumMap(
    animation: QuantumAnimation?,
    large: Boolean,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit = {},
) {
    val outgoing = large && animation?.effect == QuantumEffect.CloseMap
    Box(modifier.clipToBounds()) {
        if (!outgoing) {
            content(Modifier.matchParentSize().graphicsLayer { alpha = animation?.contentAlpha ?: 1f })
        }
        if (animation != null) {
            val particles = remember { mapParticles() }
            Canvas(Modifier.matchParentSize().testTag(if (large) "quantum-bigmap" else "quantum-map")) {
                val rim = size.minDimension * 0.5f - 1.dp.toPx()
                // Leave room to disperse outward inside the existing circular XR panel.
                val radius = if (outgoing) rim * 0.84f else rim
                drawQuantumParticles(particles, animation, center, radius, outgoing)
            }
        }
    }
}

private fun mapParticles(): List<QuantumParticle> {
    val random = Random(13)
    return List(60) { index ->
        val angle = (2 * PI * index / 60).toFloat()
        QuantumParticle(QuantumPoint(cos(angle), sin(angle)), random)
    }
}

@Composable
internal fun quantumLabelModifier(
    animation: QuantumAnimation,
    text: String,
    fontSize: Float,
    weight: FontWeight,
    scaleX: Float,
    count: Int,
    baseline: () -> Float,
): Modifier {
    val density = LocalDensity.current
    val em = with(density) { fontSize.sp.toPx() }
    val glyph = remember(animation, em, weight, count) {
        val paint = Paint().apply {
            textSize = 128f
            textScaleX = scaleX
            typeface = Typeface.create(
                if (weight == FontWeight.Medium) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL,
            )
        }
        val path = quantumTextPath(text, paint)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        path.offset(-bounds.centerX(), -bounds.centerY())
        path.transform(Matrix().apply { setScale(1f / 128, 1f / 128) })
        QuantumGlyph(
            Offset(bounds.centerX() / 128, bounds.centerY() / 128),
            sampleQuantumPath(path, count, text.hashCode()),
        )
    }
    return Modifier.drawWithContent {
        drawContent()
        val origin = Offset(glyph.offset.x * em, baseline() + glyph.offset.y * em)
        drawQuantumParticles(glyph.particles, animation, origin, em, outgoing = false)
    }.graphicsLayer { alpha = animation.contentAlpha }
}

private data class QuantumGlyph(val offset: Offset, val particles: List<QuantumParticle>)

private fun DrawScope.drawQuantumParticles(
    particles: List<QuantumParticle>,
    animation: QuantumAnimation,
    origin: Offset,
    scale: Float,
    outgoing: Boolean,
) {
    val fraction = animation.fraction
    val fade = 1f - quantumContentAlpha(fraction)
    if (scale <= 0f || fraction < 0f || fade <= 0f) return
    val frame = quantumTransitionFrame(fraction, outgoing)
    val alpha = (0.25f + 0.45f * frame.convergence) * fade
    for ((index, particle) in particles.withIndex()) {
        val color = when {
            index % 9 == 0 -> HudDetail
            index % 4 == 0 -> HudText
            else -> QuantumCyan
        }
        drawCircle(
            color,
            radius = (particle.radiusDp * 0.65f).dp.toPx(),
            center = origin + Offset(particle.x(frame) * scale, particle.y(frame) * scale),
            alpha = alpha,
        )
    }
}
