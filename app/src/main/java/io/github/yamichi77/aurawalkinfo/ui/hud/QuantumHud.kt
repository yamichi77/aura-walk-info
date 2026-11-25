package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

internal const val QuantumEffectsEnabled = true
internal const val QuantumTransitionMillis = 500
internal const val QuantumRimFlashMillis = 200
private const val FadeStart = 0.7f
private const val StartupMapLoadTimeoutMillis = 2_000L

internal enum class QuantumEffect { Startup, Walk, OpenMap, CloseMap, ReturnMap, Candidates }

/**
 * Decides when frames flow again after a heavy composition: [SettledFrames] frames in a row no longer
 * than [SettledFrameMillis] apart, or [TimeoutMillis] after the first frame at the latest.
 */
internal class QuantumFrameSettle {
    private var firstNanos = -1L
    private var lastNanos = -1L
    private var smoothFrames = 0
    var waitedMillis = 0L
        private set

    /** Feed each frame time. Returns true once the frames have settled or the wait timed out. */
    fun onFrame(frameNanos: Long): Boolean {
        if (firstNanos < 0) {
            firstNanos = frameNanos
            lastNanos = frameNanos
            return false
        }
        val interval = (frameNanos - lastNanos) / 1_000_000
        lastNanos = frameNanos
        smoothFrames = if (interval <= SettledFrameMillis) smoothFrames + 1 else 0
        waitedMillis = (frameNanos - firstNanos) / 1_000_000
        return smoothFrames >= SettledFrames || waitedMillis >= TimeoutMillis
    }

    companion object {
        const val SettledFrames = 3
        const val SettledFrameMillis = 25L
        const val TimeoutMillis = 1_000L
    }
}

internal class QuantumAnimation(val effect: QuantumEffect, private val progress: State<Float>) {
    val fraction: Float get() = progress.value
    val contentAlpha: Float get() = quantumContentAlpha(fraction)
    val drawing by derivedStateOf { fraction >= 0f }
    val gathering by derivedStateOf { fraction in 0f..<1f }
}

internal fun QuantumAnimation.revealAlpha(effectsEnabled: Boolean) =
    if (effectsEnabled) contentAlpha else fraction.coerceIn(0f, 1f)

internal fun quantumContentAlpha(fraction: Float) =
    ((fraction - FadeStart) / (1f - FadeStart)).coerceIn(0f, 1f)

internal fun quantumTransitionFrame(fraction: Float, gatherUntil: Float = FadeStart, outgoing: Boolean = false) = QuantumParticleFrame(
    convergence = if (outgoing) 1f else quantumConvergence(fraction / gatherUntil),
    dispersal = if (outgoing) 0.30f * quantumConvergence(fraction) else 0f,
    jitterPhase = fraction * QuantumTransitionMillis * 0.024f,
)

internal data class HudQuantum(
    val left: QuantumAnimation?,
    val miniMap: QuantumAnimation?,
    val onMapLoaded: (() -> Unit)? = null,
)

internal class HudQuantumStartup {
    var leftPlayed by mutableStateOf(false)
    var mapPlayed by mutableStateOf(false)
    private val loaded = CompletableDeferred<Unit>()
    val mapLoaded: Deferred<Unit> get() = loaded

    fun onMapLoaded() {
        loaded.complete(Unit)
    }
}

private class QuantumHistory {
    var phase: WalkPhase? = null
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
    val startLeft = phase != null && !startup.leftPlayed
    val startMap = visible && !startup.mapPlayed
    val left = when {
        startLeft -> QuantumEffect.Startup
        phase == null -> null
        phase == history.phase -> history.left
        history.phase is WalkPhase.Starting && phase == WalkPhase.Measuring -> QuantumEffect.Walk
        history.phase is WalkPhase.Ending && phase == WalkPhase.Summary -> QuantumEffect.Walk
        else -> null
    }
    val map = when {
        !visible || showBigMap -> null
        startMap -> QuantumEffect.Startup
        else -> history.map
    }
    SideEffect {
        if (startLeft) startup.leftPlayed = true
        if (startMap) startup.mapPlayed = true
        history.phase = phase
        history.left = left
        history.map = map
    }
    val leftAnimation = rememberQuantumAnimation(left, left == QuantumEffect.Walk && countdown?.finished == true,
        startupPanel = "Left")
    val waitForMap = map == QuantumEffect.Startup && clusters?.mapCenter != null && rememberHasMapsKey()
    val mapAnimation = rememberQuantumAnimation(map, startupPanel = "MiniMap",
        mapLoaded = startup.mapLoaded.takeIf { waitForMap })
    return HudQuantum(leftAnimation, mapAnimation,
        if (waitForMap && mapAnimation != null) startup::onMapLoaded else null)
}

@Composable
internal fun rememberQuantumAnimation(
    effect: QuantumEffect?,
    waitForRipple: Boolean = false,
    startupPanel: String = "",
    mapLoaded: Deferred<Unit>? = null,
): QuantumAnimation? {
    if (effect == null) return null
    val startup = effect == QuantumEffect.Startup
    val progress = remember(effect) { mutableFloatStateOf(if (waitForRipple || startup) -1f else 0f) }
    var finished by remember(effect) { mutableStateOf(false) }
    var settled by remember(effect) { mutableStateOf(!startup) }
    if (!settled) LaunchedEffect(mapLoaded) {
        val start = SystemClock.uptimeMillis()
        if (mapLoaded != null) withTimeoutOrNull(StartupMapLoadTimeoutMillis) { mapLoaded.await() }
        val loadWait = SystemClock.uptimeMillis() - start
        val settle = QuantumFrameSettle()
        while (!settle.onFrame(withFrameNanos { it })) Unit
        Log.i("AuraQuantum", "Startup$startupPanel settled waitMillis=${SystemClock.uptimeMillis() - start}" +
            " mapWaitMillis=$loadWait settleMillis=${settle.waitedMillis}")
        settled = true
    }
    LaunchedEffect(effect, waitForRipple, settled) {
        if (settled && !waitForRipple && !finished) {
            val duration = QuantumTransitionMillis
            var previous = -1L
            var longest = 0L
            animate(0f, duration.toFloat(), animationSpec = tween(duration, easing = LinearEasing)) { value, _ ->
                progress.floatValue = value / QuantumTransitionMillis
                if (startup) {
                    val now = SystemClock.uptimeMillis()
                    if (previous >= 0L) longest = maxOf(longest, now - previous)
                    previous = now
                }
            }
            if (startup) Log.i("AuraQuantum", "Startup$startupPanel done longestFrameMillis=$longest")
            finished = true
        }
    }
    return if (finished) null else remember(effect) { QuantumAnimation(effect, progress) }
}

@Composable
internal fun QuantumMap(
    animation: QuantumAnimation?,
    modifier: Modifier,
    panelScale: Float,
    effectsEnabled: Boolean,
    large: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    val outgoing = animation?.effect == QuantumEffect.CloseMap
    Box(modifier.clipToBounds()) {
        // Keep the same AndroidView composed even while only the outgoing particles are drawn.
        content(Modifier.matchParentSize().graphicsLayer {
            alpha = when {
                animation == null -> 1f
                !outgoing -> animation.revealAlpha(effectsEnabled)
                effectsEnabled -> 0f
                else -> 1f - animation.revealAlpha(false)
            }
        })
        if (effectsEnabled && animation?.gathering == true) {
            val particles = remember { quantumRingParticles(60) }
            Canvas(Modifier.matchParentSize().testTag(if (large) "quantum-bigmap" else "quantum-map")) {
                val rim = size.minDimension * 0.5f - 1.dp.toPx() / panelScale
                val radius = if (outgoing) rim * 0.84f else rim
                drawQuantumParticles(particles, animation, center, radius, pixelScale = panelScale)
            }
        }
        if (large && effectsEnabled) QuantumRimFlash(animation, Modifier.matchParentSize(), "quantum-bigmap-rim")
    }
}

internal fun quantumRingParticles(count: Int): List<QuantumParticle> {
    val random = Random(13)
    return List(count) { index ->
        val angle = (2 * PI * index / count).toFloat()
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
    left: Float = 0f,
    contentOpacity: Float = 1f,
    baseline: () -> Float,
): Modifier {
    val density = LocalDensity.current
    val em = with(density) { fontSize.sp.toPx() }
    val glyph = remember(text, em, weight, count, scaleX) {
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
        val origin = Offset(left + glyph.offset.x * em, baseline() + glyph.offset.y * em)
        drawQuantumParticles(glyph.particles, animation, origin, em)
    }.graphicsLayer { alpha = animation.contentAlpha * contentOpacity }
}

private data class QuantumGlyph(val offset: Offset, val particles: List<QuantumParticle>)

internal data class QuantumParticleStyle(val radiusDp: Float, val alpha: Float)

internal fun quantumParticleStyle(fraction: Float, radiusDp: Float): QuantumParticleStyle? =
    if (fraction in 0f..<1f) QuantumParticleStyle(radiusDp * 0.65f * 1.5f, 1f) else null

internal fun quantumRimFlashStrength(elapsedMillis: Float): Float =
    if (elapsedMillis >= 0f) (1f - elapsedMillis / QuantumRimFlashMillis).coerceAtLeast(0f) else 0f

@Composable
internal fun QuantumRimFlash(animation: QuantumAnimation?, modifier: Modifier, tag: String) {
    if (animation?.effect != QuantumEffect.OpenMap) return
    val visible by remember(animation) {
        derivedStateOf { rimStrength(animation) > 0f }
    }
    if (visible) Canvas(modifier.testTag(tag)) {
        val strength = rimStrength(animation)
        val stroke = (1f + 2f * strength).dp.toPx()
        drawCircle(HudText, (size.minDimension - stroke) / 2, alpha = strength, style = Stroke(stroke))
    }
}

private fun rimStrength(animation: QuantumAnimation): Float =
    quantumRimFlashStrength(animation.fraction * QuantumTransitionMillis - QuantumTransitionMillis)

internal fun DrawScope.drawQuantumParticles(
    particles: List<QuantumParticle>,
    animation: QuantumAnimation,
    origin: Offset,
    scale: Float,
    pixelScale: Float = 1f,
) {
    val fraction = animation.fraction
    if (scale <= 0f || !animation.gathering) return
    val gatherUntil = if (animation.effect == QuantumEffect.OpenMap) 1f else FadeStart
    val frame = quantumTransitionFrame(fraction, gatherUntil, outgoing = animation.effect == QuantumEffect.CloseMap)
    for ((index, particle) in particles.withIndex()) {
        val style = quantumParticleStyle(fraction, particle.radiusDp) ?: continue
        val color = when {
            index % 9 == 0 -> HudDetail
            index % 4 == 0 -> HudText
            else -> QuantumCyan
        }
        drawCircle(
            color,
            radius = style.radiusDp.dp.toPx() / pixelScale,
            center = origin + Offset(particle.x(frame) * scale, particle.y(frame) * scale),
            alpha = style.alpha,
        )
    }
}
