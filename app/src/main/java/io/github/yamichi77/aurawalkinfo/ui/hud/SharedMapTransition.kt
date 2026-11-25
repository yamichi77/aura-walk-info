package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.log2

internal const val MiniMapZoom = 17.5f

internal fun compensatedMapZoom(zoom: Float, miniScale: Float) = zoom - log2(miniScale)

internal data class SharedMapPresentation(
    val large: Boolean,
    val hidden: Boolean = false,
    val animation: QuantumAnimation? = null,
) {
    val acceptsLargeInput get() = large && !hidden && (animation == null ||
        animation.effect == QuantumEffect.OpenMap && !animation.gathering)
    val settledMini get() = !large && !hidden && animation == null

    fun buttonsAlpha(effectsEnabled: Boolean): Float = when {
        !large || hidden -> 0f
        animation?.effect == QuantumEffect.CloseMap -> 1f - animation.revealAlpha(effectsEnabled)
        !effectsEnabled && animation != null -> animation.revealAlpha(false)
        else -> 1f
    }
}

@Composable
internal fun rememberSharedMapPresentation(open: Boolean, visible: Boolean, effectsEnabled: Boolean): SharedMapPresentation {
    var map by remember(visible) { mutableStateOf(SharedMapPresentation(large = visible && open)) }
    LaunchedEffect(open, visible, effectsEnabled) {
        if (!visible || (map.large == open && !map.hidden && map.animation == null)) return@LaunchedEffect
        var longestNanos = 0L
        suspend fun play(large: Boolean, effect: QuantumEffect) {
            val reveal = effect != QuantumEffect.CloseMap
            val progress = mutableFloatStateOf(if (reveal) -1f else 0f)
            val phase = SharedMapPresentation(large, animation = QuantumAnimation(effect, progress))
            if (reveal) {
                // Prepare both the XR entity and its Compose content while transparent. The two
                // compositions can apply in different frames; neither should expose the old tiles.
                map = map.copy(hidden = true)
                withFrameNanos { }
                map = phase.copy(hidden = true)
                val settle = QuantumFrameSettle()
                while (!settle.onFrame(withFrameNanos { it })) Unit
                progress.floatValue = 0f
            }
            map = phase
            val gather = if (effectsEnabled) QuantumTransitionMillis else HudAnimationMillis
            // Keep measuring through the buttons' single orbit after the opening ring gathers.
            val duration = gather + if (effectsEnabled && effect == QuantumEffect.OpenMap) COUNTDOWN_SECOND_MILLIS.toInt() else 0
            var previous = -1L
            AnimationState(0f).animateTo(duration.toFloat(), tween(duration, easing = LinearEasing)) {
                if (previous >= 0L) longestNanos = maxOf(longestNanos, lastFrameTimeNanos - previous)
                previous = lastFrameTimeNanos
                // Once the flash ends only the buttons' own orbit animation needs to redraw.
                progress.floatValue = value.coerceAtMost((gather + QuantumRimFlashMillis).toFloat()) / gather
            }
        }
        try {
            if (!open && map.large && !map.hidden) play(true, QuantumEffect.CloseMap)
            play(open, if (open) QuantumEffect.OpenMap else QuantumEffect.ReturnMap)
            map = SharedMapPresentation(large = open)
        } finally {
            val direction = if (open) "open" else "close"
            Log.i("AuraBigMap", "transition=$direction longestFrameMillis=${longestNanos / 1_000_000f} resizeMillis=0")
        }
    }
    return map
}

@Composable
internal fun rememberMapSpec(spec: HudVisualSpec, locationDenied: Boolean): HudVisualSpec.Clusters? {
    val current = spec as? HudVisualSpec.Clusters
    var last by remember(locationDenied) { mutableStateOf<HudVisualSpec.Clusters?>(null) }
    if (locationDenied) return null
    if (current != null) SideEffect { last = current }
    return current ?: last.takeIf { spec is HudVisualSpec.Fast }
}
