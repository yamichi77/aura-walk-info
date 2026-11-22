package io.github.yamichi77.aurawalkinfo.diagnostics

import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private const val MAP_LOAD_TIMEOUT_MS = 15_000L
private const val SNAPSHOT_TIMEOUT_MS = 5_000L

@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun MapProbe(mode: String, capture: Boolean, spatial: Boolean) {
    val context = LocalContext.current
    val startedAt = remember { SystemClock.elapsedRealtime() }
    val loaded = remember { CompletableDeferred<Unit>() }
    val mapSize = remember { mutableStateOf(IntSize.Zero) }
    val label = "$mode ${if (spatial) "SpatialPanel" else "2D"}"
    LaunchedEffect(Unit) {
        if (withTimeoutOrNull(MAP_LOAD_TIMEOUT_MS) { loaded.await() } == null) {
            logProbe("$label: onMapLoaded=false elapsed=${elapsedSeconds(startedAt)}s")
        }
    }
    val camera = rememberCameraPositionState {
        // Use a fixed public landmark for rendering tests.
        position = CameraPosition.fromLatLngZoom(LatLng(35.68124, 139.76712), 17f)
    }
    GoogleMap(
        modifier = Modifier.fillMaxSize().onSizeChanged { mapSize.value = it },
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(mode == "maplite") },
        uiSettings = MapUiSettings(
            mapToolbarEnabled = false,
            zoomControlsEnabled = false,
            scrollGesturesEnabled = false,
            zoomGesturesEnabled = false,
            tiltGesturesEnabled = false,
            rotationGesturesEnabled = false,
        ),
        onMapLoaded = {
            if (loaded.complete(Unit)) logProbe("$label: onMapLoaded=true elapsed=${elapsedSeconds(startedAt)}s")
        },
    ) {
        MapEffect(mode) { map ->
            logProbe("$label: GoogleMap ready")
            val loadWait = (MAP_LOAD_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt))
                .coerceAtLeast(1L)
            withTimeoutOrNull(loadWait) { loaded.await() }
            // With the display asleep, the SDK can crash on its snapshot worker with a 0x0 surface.
            val size = mapSize.value
            val interactive = context.getSystemService(PowerManager::class.java).isInteractive
            if (!interactive || size.width <= 0 || size.height <= 0) {
                logProbe("$label: snapshot skipped (interactive=$interactive, layout=${size.width}x${size.height})")
                return@MapEffect
            }
            try {
                val result = CompletableDeferred<Bitmap?>()
                map.snapshot { result.complete(it) }
                val bitmap = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { result.await() }
                if (bitmap == null) {
                    logProbe("$label: snapshot unavailable (${if (result.isCompleted) "null" else "timeout"})")
                } else {
                    logProbe("$label: snapshot ${withContext(Dispatchers.Default) { pixelSummary(bitmap) }}")
                    if (capture) saveProbePng(context, "$mode-snapshot.png", bitmap)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logProbe("$label: snapshot failed (${error.javaClass.simpleName}: ${error.message})")
            }
        }
    }
}

private fun pixelSummary(bitmap: Bitmap): String {
    val row = IntArray(bitmap.width)
    var nonBlack = 0L
    var brightness = 0.0
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (pixel in row) {
            val alpha = (pixel ushr 24) / 255.0
            val rgb = pixel and 0xFFFFFF
            if (alpha > 0 && rgb != 0) nonBlack++
            brightness += alpha * (
                0.2126 * ((rgb shr 16) and 255) +
                    0.7152 * ((rgb shr 8) and 255) +
                    0.0722 * (rgb and 255)
                )
        }
    }
    val count = bitmap.width.toLong() * bitmap.height
    return String.format(
        Locale.ROOT, "%dx%d nonBlack=%.2f%% meanBrightness=%.2f/255",
        bitmap.width, bitmap.height, nonBlack * 100.0 / count, brightness / count,
    )
}
