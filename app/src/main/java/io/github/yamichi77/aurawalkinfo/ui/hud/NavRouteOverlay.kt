package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.navPoint
import io.github.yamichi77.aurawalkinfo.ui.theme.HudArrow
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlinx.coroutines.awaitCancellation

internal typealias ProjectNavPoints = (List<NavPoint>) -> List<Offset>

@OptIn(MapsComposeExperimentalApi::class)
@Composable
@GoogleMapComposable
internal fun NavMapProjectionEffect(camera: CameraPositionState, onProjection: (ProjectNavPoints?) -> Unit) {
    val report by rememberUpdatedState(onProjection)
    MapEffect(camera) { map ->
        report { points ->
            // Read camera state in the draw phase so camera motion invalidates the overlay.
            camera.position
            val projection = map.projection
            points.map {
                val pixel = projection.toScreenLocation(LatLng(it.latitude, it.longitude))
                Offset(pixel.x.toFloat(), pixel.y.toFloat())
            }
        }
        try {
            awaitCancellation()
        } finally {
            report(null)
        }
    }
}

@Composable
internal fun NavRouteOverlay(
    route: NavMapRoute,
    bigMap: Boolean,
    project: ProjectNavPoints,
    modifier: Modifier = Modifier,
    panelScale: Float = 1f,
) {
    Canvas(modifier.clip(CircleShape)) {
        val points = project(route.remaining + route.destination + listOfNotNull(route.nextTurn))
        val line = points.take(route.remaining.size)
        drawRouteLine(line, route.opacity, width = (if (bigMap) 4f else 3f) / panelScale)
        drawCircle(HudArrow, radius = 5f / panelScale, center = points[line.size], style = Stroke(1.5f / panelScale))
        if (route.nextTurn != null) drawCircle(HudArrow, radius = 3f / panelScale, center = points.last())
    }
}

@Composable
internal fun RoutePreviewOverlay(preview: NavState.Preview, project: ProjectNavPoints, modifier: Modifier = Modifier) {
    val coordinates = remember(preview) { preview.points() }
    Canvas(modifier.clip(CircleShape).testTag("route-preview-overlay")) {
        val points = project(coordinates)
        val start = points[points.lastIndex - 1]
        val goal = points.last()
        drawRouteLine(points.dropLast(2), 1f, width = 4f)
        drawCircle(HudText, 3.dp.toPx(), start)
        val radius = 4.dp.toPx()
        val head = goal - Offset(0f, radius * 1.7f)
        drawPath(Path().apply {
            moveTo(head.x - radius, head.y)
            quadraticTo(head.x - radius, head.y + radius * 0.7f, goal.x, goal.y)
            quadraticTo(head.x + radius, head.y + radius * 0.7f, head.x + radius, head.y)
        }, HudText, style = Stroke(1.5.dp.toPx()))
        drawCircle(HudText, radius, head, style = Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.drawRouteLine(points: List<Offset>, opacity: Float, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (point in points.drop(1)) lineTo(point.x, point.y)
    }
    drawPath(path, HudArrow.copy(alpha = opacity), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun NavState.Preview.points() = route.polyline + origin.navPoint() + destination.point

internal fun routePreviewSummary(route: NavRoute): String {
    val duration = route.duration
    val minutes = duration.toMinutes() + if (duration.seconds % 60 != 0L || duration.nano != 0) 1 else 0
    return "約 ${minutes}分・${navDistance(route.distanceMeters.toDouble())}"
}

internal data class RoutePreviewCamera(val preview: NavState.Preview, val tilt: Float, val size: IntSize) {
    fun fit(map: GoogleMap) {
        val diameter = minOf(size.width, size.height)
        if (diameter <= 0) return
        val bounds = LatLngBounds.builder().apply {
            for (point in preview.points()) include(LatLng(point.latitude, point.longitude))
        }.build()
        // An inset square leaves space for the pin and the summary inside the circular clip.
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, size.width, size.height, diameter / 4))
        if (tilt == 0f) return

        // The bounds update resets tilt. Restore it and allow for the near edge expanding in perspective.
        val flat = map.cameraPosition
        val corners = listOf(
            bounds.southwest, bounds.northeast,
            LatLng(bounds.southwest.latitude, bounds.northeast.longitude),
            LatLng(bounds.northeast.latitude, bounds.southwest.longitude),
        )
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = diameter * 0.35f
        var zoom = flat.zoom
        while (true) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(flat.target, zoom, tilt, 0f)))
            val projection = map.projection
            if (corners.all {
                val pixel = projection.toScreenLocation(it)
                (Offset(pixel.x.toFloat(), pixel.y.toFloat()) - center).getDistance() <= radius
            } || zoom <= map.minZoomLevel) break
            zoom = (zoom - 0.5f).coerceAtLeast(map.minZoomLevel)
        }
    }
}
