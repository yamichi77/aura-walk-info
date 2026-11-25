package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
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
    opacity: Float = spec.opacity,
    lineOpacity: Float = spec.mapLineOpacity,
    panelScale: Float = 1f,
    markerPosition: (Offset) -> Offset? = { it },
) {
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
    Canvas(modifier.clip(CircleShape).alpha(opacity)) {
        val radius = size.minDimension / 2
        val markerRadius = (arrowHeight?.toPx() ?: mapArrowHeight(size.minDimension)) / 2
        val stroke = 1f / panelScale
        drawCircle(HudText.copy(alpha = lineOpacity), radius - stroke / 2, style = Stroke(stroke))
        val marker = markerPosition(center) ?: return@Canvas
        if ((marker - center).getDistance() > radius) return@Canvas
        if (angle == null) {
            drawCircle(spec.mapArrowColor, markerRadius, marker)
        } else {
            rotate(angle.value, pivot = marker) {
                drawPath(
                    Path().apply {
                        moveTo(marker.x, marker.y - markerRadius)
                        lineTo(marker.x - markerRadius * 0.7f, marker.y + markerRadius)
                        lineTo(marker.x, marker.y + markerRadius * 0.55f)
                        lineTo(marker.x + markerRadius * 0.7f, marker.y + markerRadius)
                        close()
                    },
                    spec.mapArrowColor,
                )
            }
        }
    }
}
