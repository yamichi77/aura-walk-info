package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal fun mapArrowHeight(diameter: Float) = diameter * 0.3f

private fun normalizeBearing(degrees: Float) = (degrees % 360f + 360f) % 360f

internal fun shortestArrowTarget(current: Float, bearing: Float): Float {
    val delta = normalizeBearing(bearing - current + 180f) - 180f
    return current + delta
}

@Composable
internal fun MiniMapPlaceholder(
    spec: HudVisualSpec.Clusters,
    modifier: Modifier = Modifier,
    arrowHeight: Dp? = null,
    selfCheck: Boolean = false,
) {
    val opacity by animateFloatAsState(spec.opacity, tween(HudAnimationMillis))
    val bearing = spec.mapBearingDeg
    val angle = if (bearing != null) remember { Animatable(bearing) } else null
    LaunchedEffect(bearing, selfCheck) {
        if (angle != null && bearing != null) {
            val target = shortestArrowTarget(angle.value, bearing)
            if (angle.value != target) angle.animateTo(target, tween(HudAnimationMillis))
            // Keep the settled value bounded without changing the displayed direction.
            angle.snapTo(bearing)
        }
        if (selfCheck) Log.i(MAP_LOG_TAG, "marker arrowAngle=${angle?.value}")
    }
    Canvas(modifier.alpha(opacity)) {
        val radius = size.minDimension / 2
        val markerRadius = (arrowHeight?.toPx() ?: mapArrowHeight(size.minDimension)) / 2
        drawCircle(HudText.copy(alpha = spec.mapLineOpacity), radius - 0.5f, style = Stroke(1f))
        if (angle == null) {
            drawCircle(spec.mapArrowColor, markerRadius)
        } else {
            rotate(angle.value) {
                drawPath(
                    Path().apply {
                        moveTo(center.x, center.y - markerRadius)
                        lineTo(center.x - markerRadius * 0.7f, center.y + markerRadius)
                        lineTo(center.x, center.y + markerRadius * 0.55f)
                        lineTo(center.x + markerRadius * 0.7f, center.y + markerRadius)
                        close()
                    },
                    spec.mapArrowColor,
                )
            }
        }
    }
}
