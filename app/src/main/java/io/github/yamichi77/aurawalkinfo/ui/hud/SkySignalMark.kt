package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness

internal val PicsStaleColor = Color(0xFF888780)

internal data class SkySignalMark(val freshness: PicsFreshness, val dots: List<PicsAspect>)

internal fun PicsCandidate.toSkySignalMark() = SkySignalMark(freshness, lights.take(4).map { it.aspect })

internal fun DrawScope.drawPicsDots(display: SkySignalMark, origin: Offset, diameter: Float) {
    val fresh = display.freshness == PicsFreshness.FRESH
    display.dots.forEachIndexed { index, aspect ->
        val color = picsDotColor(fresh, aspect)
        val dx = (index - (display.dots.size - 1) / 2f) * diameter * 0.28f
        drawCircle(
            color, diameter * 0.09f, origin + Offset(dx, diameter * 0.72f),
            style = if (fresh && aspect == PicsAspect.FLASHING_GREEN) Stroke(1f) else Fill,
        )
    }
}

private fun picsDotColor(fresh: Boolean, aspect: PicsAspect): Color = when {
    !fresh -> PicsStaleColor
    aspect == PicsAspect.RED -> Color(0xFFE07A7A)
    aspect == PicsAspect.GREEN || aspect == PicsAspect.FLASHING_GREEN -> Color(0xFF7FBFA8)
    else -> PicsStaleColor
}
