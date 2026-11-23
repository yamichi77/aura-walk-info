package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import io.github.yamichi77.aurawalkinfo.session.MapCenter

@Composable
internal fun BoxWithConstraintsScope.PicsIntersectionLayer(
    spec: HudVisualSpec.Clusters,
    camera: CameraPositionState,
    bigMapMode: BigMapMode?,
    mapOpacity: Float,
) {
    if (spec.showSafetyNotice) return
    val (display, alpha) = rememberFadingPics(spec.pics) ?: return
    val point = projectedIntersection(camera, display.position, maxWidth, maxHeight) ?: return
    val mapSize = if (bigMapMode != null) HudPlacement.bigMapSizeMeters else HudPlacement.mapSizeMeters(spec.mapDiameter)
    val ringDiameter = minOf(maxWidth, maxHeight) * (2 * HudPlacement.planeHalfSpan(0.5f) / mapSize)
    PicsMapOverlay(
        display, point, ringDiameter, bigMapMode,
        Modifier.matchParentSize().clip(CircleShape).alpha(mapOpacity * alpha),
    )
}

@Composable
private fun projectedIntersection(
    camera: CameraPositionState,
    intersection: MapCenter,
    mapWidth: Dp,
    mapHeight: Dp,
): IntOffset? {
    val projection = camera.projection ?: return null
    // Reproject against the SDK camera during zoom/tilt animations and on map resize.
    return remember(camera.position, camera.isMoving, intersection, mapWidth, mapHeight, LocalDensity.current) {
        val point = projection.toScreenLocation(LatLng(intersection.latitude, intersection.longitude))
        IntOffset(point.x, point.y)
    }
}

@Composable
internal fun PicsMapOverlay(
    display: PicsHudDisplay,
    point: IntOffset,
    ringDiameter: Dp,
    bigMapMode: BigMapMode?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val fromCenter = Offset(point.x - constraints.maxWidth / 2f, point.y - constraints.maxHeight / 2f)
        val radius = minOf(constraints.maxWidth, constraints.maxHeight) / 2f
        if (radius <= 0f || fromCenter.getDistanceSquared() > radius * radius) return@BoxWithConstraints

        val fresh = display.freshness == PicsFreshness.FRESH
        Canvas(Modifier.matchParentSize().testTag("pics-map-mark")) {
            val diameter = ringDiameter.toPx()
            val origin = Offset(point.x.toFloat(), point.y.toFloat())
            drawCircle(
                if (fresh) Color(0x99E0F7FF) else PicsStaleColor,
                diameter / 2f, origin, style = Stroke(1f),
            )
            drawPicsDots(display, origin, diameter)
        }
        if (bigMapMode != null) {
            val labelHeight = 22.dp
            val labelSize = with(LocalDensity.current) { 14.dp.toSp().value }
            Box(
                Modifier.offset {
                    point + IntOffset((ringDiameter / 2 + 4.dp).roundToPx(), -(labelHeight / 2).roundToPx())
                }.height(labelHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                HudLabel(display.label, labelSize, display.labelColor)
            }
        }
    }
}

internal fun DrawScope.drawPicsDots(display: PicsHudDisplay, origin: Offset, diameter: Float) {
    val fresh = display.freshness == PicsFreshness.FRESH
    display.dots.forEachIndexed { index, aspect ->
        val color = when {
            !fresh -> PicsStaleColor
            aspect == PicsAspect.RED -> Color(0xFFE07A7A)
            aspect == PicsAspect.GREEN || aspect == PicsAspect.FLASHING_GREEN -> Color(0xFF7FBFA8)
            else -> PicsStaleColor
        }
        val dx = (index - (display.dots.size - 1) / 2f) * diameter * 0.28f
        drawCircle(
            color, diameter * 0.09f, origin + Offset(dx, diameter * 0.72f),
            style = if (fresh && aspect == PicsAspect.FLASHING_GREEN) Stroke(1f) else Fill,
        )
    }
}
