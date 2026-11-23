package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal fun mapArrowHeight(diameter: Float) = diameter * 0.3f

@Composable
internal fun MiniMapPlaceholder(
    spec: HudVisualSpec.Clusters,
    modifier: Modifier = Modifier,
    arrowHeight: Dp? = null,
) {
    val opacity by animateFloatAsState(spec.opacity, tween(HudAnimationMillis))
    Canvas(modifier.alpha(opacity)) {
        val radius = size.minDimension / 2
        val arrow = (arrowHeight?.toPx() ?: mapArrowHeight(size.minDimension)) / 2
        drawCircle(HudText.copy(alpha = spec.mapLineOpacity), radius - 0.5f, style = Stroke(1f))
        drawPath(
            Path().apply {
                moveTo(center.x, center.y - arrow)
                lineTo(center.x - arrow * 0.7f, center.y + arrow)
                lineTo(center.x, center.y + arrow * 0.55f)
                lineTo(center.x + arrow * 0.7f, center.y + arrow)
                close()
            },
            spec.mapArrowColor,
        )
    }
}
