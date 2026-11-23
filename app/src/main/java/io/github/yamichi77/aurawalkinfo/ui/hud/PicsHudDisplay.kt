package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import kotlinx.coroutines.delay

internal val PicsStaleColor = Color(0xFF888780)
private const val ReferenceMillis = 6_000L
private const val ReferenceText = "信号は参考表示です"

internal data class PicsHudDisplay(
    val position: MapCenter,
    val freshness: PicsFreshness,
    val label: String,
    val dots: List<PicsAspect>,
) {
    val labelColor: Color
        get() = if (freshness == PicsFreshness.FRESH) Color(0xFF9FE1CB) else PicsStaleColor
}

internal fun PicsCandidate.toHudDisplay() =
    PicsHudDisplay(position, freshness, formatPicsAspects(lights), lights.take(4).map { it.aspect })

internal fun formatPicsAspects(lights: List<PicsLight>): String {
    // The parser keeps the lights in their wire order, which is their numbered order.
    val labels = lights.mapNotNull { light ->
        val aspect = when (light.aspect) {
            PicsAspect.RED -> "赤"
            PicsAspect.GREEN -> "青"
            PicsAspect.FLASHING_GREEN -> "青点滅"
            else -> return@mapNotNull null
        }
        aspect + (light.remaining?.toString() ?: "")
    }
    if (labels.isEmpty()) return "情報なし"
    return labels.take(3).joinToString("・") + if (labels.size > 3) "…" else ""
}

/** Kept for the lifetime of the app process and started by the first composed signal row. */
internal class PicsReferenceNotice(private val clock: Clock) {
    private var deadline: Long? = null

    fun remainingMillis(): Long =
        deadline?.let { (it - clock.elapsedRealtimeMillis()).coerceAtLeast(0L) } ?: ReferenceMillis

    fun start() {
        if (deadline == null) deadline = clock.elapsedRealtimeMillis() + ReferenceMillis
    }
}

internal val LocalPicsReferenceNotice = staticCompositionLocalOf<PicsReferenceNotice?> { null }

internal data class FadingPicsDisplay(val display: PicsHudDisplay, val alpha: Float, val isFading: Boolean)

@Composable
internal fun rememberFadingPics(display: PicsHudDisplay?): FadingPicsDisplay? {
    var last by remember { mutableStateOf<PicsHudDisplay?>(null) }
    val alpha by animateFloatAsState(
        if (display == null) 0f else 1f,
        if (display == null) tween(HudAnimationMillis) else snap(),
    )
    SideEffect {
        if (display != null || alpha == 0f) last = display
    }
    return when {
        display != null -> FadingPicsDisplay(display, 1f, isFading = false)
        alpha > 0f -> last?.let { FadingPicsDisplay(it, alpha, isFading = true) }
        else -> null
    }
}

@Composable
internal fun PicsStatsLabel(pics: FadingPicsDisplay, textScale: Float, widthPx: Float) {
    val (display, alpha) = pics
    val notice = LocalPicsReferenceNotice.current
    var showReference by remember(notice) { mutableStateOf((notice?.remainingMillis() ?: 0L) > 0L) }
    LaunchedEffect(notice) {
        if (notice != null) {
            notice.start()
            delay(notice.remainingMillis())
            showReference = false
        }
    }
    val activeText = if (showReference) ReferenceText else "信号 ${display.label}"
    var lastText by remember { mutableStateOf(activeText) }
    val text = if (pics.isFading) lastText else activeText
    if (!pics.isFading) SideEffect { lastText = activeText }
    if (!pics.isFading && LocalContext.current.isDebugBuild()) {
        LaunchedEffect(text, display.freshness) {
            Log.i("AuraPics", "PICS display phase=${display.freshness} text=$text")
        }
    }
    HudLabel(text, 14 * textScale, display.labelColor, Modifier.graphicsLayer { this.alpha = alpha }, widthPx = widthPx)
}
