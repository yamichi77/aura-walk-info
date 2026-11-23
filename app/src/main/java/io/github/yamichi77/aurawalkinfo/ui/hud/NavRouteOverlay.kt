package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.ui.theme.HudArrow
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
) {
    Canvas(modifier.clip(CircleShape)) {
        val points = project(route.remaining + route.destination + listOfNotNull(route.nextTurn))
        val line = points.take(route.remaining.size)
        if (line.size > 1) {
            val path = Path().apply {
                moveTo(line[0].x, line[0].y)
                for (point in line.drop(1)) lineTo(point.x, point.y)
            }
            drawPath(
                path, HudArrow.copy(alpha = route.opacity),
                style = Stroke(if (bigMap) 4f else 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawCircle(HudArrow, radius = 5f, center = points[line.size], style = Stroke(1.5f))
        if (route.nextTurn != null) drawCircle(HudArrow, radius = 3f, center = points.last())
    }
}
