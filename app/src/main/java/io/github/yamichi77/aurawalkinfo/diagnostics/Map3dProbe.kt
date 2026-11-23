package io.github.yamichi77.aurawalkinfo.diagnostics

import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.size
import androidx.xr.scenecore.scene
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import io.github.yamichi77.aurawalkinfo.ui.hud.BigMapMode
import io.github.yamichi77.aurawalkinfo.ui.hud.HudPlacement
import io.github.yamichi77.aurawalkinfo.ui.hud.MAP_LOG_TAG
import io.github.yamichi77.aurawalkinfo.ui.hud.MiniMapSurface
import io.github.yamichi77.aurawalkinfo.ui.hud.centeredMapPadding
import io.github.yamichi77.aurawalkinfo.ui.hud.hasMapsKey
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val CASE_DURATION_MS = 8_000L
private const val LOAD_TIMEOUT_MS = 5_500L
private const val SNAPSHOT_TIMEOUT_MS = 1_500L

private data class ProbeCase(
    val id: String,
    val mode: BigMapMode,
    val tilt: Float = mode.tilt,
    val zoom: Float = mode.zoom,
)

private val probeCases = listOf(
    ProbeCase("2d", BigMapMode.FLAT),
    ProbeCase("t45", BigMapMode.TILTED, tilt = 45f, zoom = 17f),
    ProbeCase("t60", BigMapMode.TILTED),
    ProbeCase("t675", BigMapMode.TILTED, tilt = 67.5f),
)

@Composable
internal fun Map3dProbe() {
    val context = LocalContext.current
    val hasKey = remember(context) { context.hasMapsKey() }
    val session = LocalSession.current
    if (!hasKey || session == null || !LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        LaunchedEffect(hasKey) {
            Log.i(MAP_LOG_TAG, if (!hasKey) "map3d SKIP key=missing" else "map3d FAIL spatial UI unavailable")
        }
        return
    }
    Subspace {
        val pixels = session.scene.virtualPixelDensity.convertMetersToPixels(HudPlacement.bigMapSizeMeters)
        SpatialPanel(SubspaceModifier.size((pixels / LocalDensity.current.density).dp)) {
            var index by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                repeat(probeCases.size) {
                    delay(CASE_DURATION_MS)
                    index++
                }
                Log.i(MAP_LOG_TAG, "map3d DONE")
            }
            probeCases.getOrNull(index)?.let { probe ->
                key(probe.id) {
                    // Fixed public landmark (Tokyo Station), shared with the existing map probe.
                    val position = CameraPosition(LatLng(35.68124, 139.76712), probe.zoom, probe.tilt, 0f)
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        MiniMapSurface(
                            position = position,
                            padding = centeredMapPadding(minOf(maxWidth, maxHeight)),
                            selfCheck = false,
                            checkSnapshot = false,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            mapStyle = probe.mode.style,
                            buildingsEnabled = probe.mode.buildingsEnabled,
                            probeCase = probe.id,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Map3dSnapshotCheck(
    caseId: String,
    requested: CameraPosition,
    loaded: CompletableDeferred<Long>,
    sdkMap: CompletableDeferred<GoogleMap>,
    mapSize: () -> IntSize,
) {
    val context = LocalContext.current
    LaunchedEffect(caseId) {
        val label = "map3d case=$caseId requestedTilt=${requested.tilt} requestedZoom=${requested.zoom}"
        try {
            val loadMillis = withTimeoutOrNull(LOAD_TIMEOUT_MS) { loaded.await() }
            if (!sdkMap.isCompleted) {
                Log.i(MAP_LOG_TAG, "$label FAIL actualTilt=unavailable loadSeconds=unavailable map not ready")
                return@LaunchedEffect
            }
            val map = sdkMap.await()
            val size = mapSize()
            val actual = map.cameraPosition
            val loadSeconds = loadMillis?.let { String.format(Locale.ROOT, "%.3f", it / 1000.0) } ?: "timeout"
            val camera = "$label actualTilt=${actual.tilt} actualZoom=${actual.zoom} bearing=${actual.bearing} " +
                "loadSeconds=$loadSeconds"
            val interactive = context.getSystemService(PowerManager::class.java).isInteractive
            if (!interactive || size.width <= 0 || size.height <= 0) {
                Log.i(MAP_LOG_TAG, "$camera FAIL snapshot skipped interactive=$interactive layout=$size")
                return@LaunchedEffect
            }
            val target = map.projection.toScreenLocation(requested.target)
            val offset = hypot(target.x - size.width / 2.0, target.y - size.height / 2.0)
            val snapshot = CompletableDeferred<Bitmap?>()
            map.snapshot { snapshot.complete(it) }
            val bitmap = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { snapshot.await() }
            if (bitmap == null) {
                Log.i(MAP_LOG_TAG, "$camera FAIL snapshot unavailable")
                return@LaunchedEffect
            }
            val pixels = withContext(Dispatchers.Default) { circlePixels(bitmap) }
            val filename = "map3d-$caseId.png"
            saveProbePng(context, filename, bitmap)
            val passed = loadMillis != null && pixels.nonBlack >= 1.0 && offset <= 2.0 &&
                abs(actual.tilt - requested.tilt) <= 0.5f && abs(actual.zoom - requested.zoom) <= 0.1f &&
                actual.bearing == 0f && map.isBuildingsEnabled == (requested.tilt > 0f)
            Log.i(MAP_LOG_TAG, String.format(
                Locale.ROOT, "%s %s centerOffsetPx=%.2f nonBlack=%.2f%% meanBrightness=%.2f/255 " +
                    "maxBrightness=%.2f/255 snapshot=%s",
                camera, if (passed) "PASS" else "FAIL", offset, pixels.nonBlack, pixels.mean, pixels.max, filename,
            ))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.i(MAP_LOG_TAG, "$label FAIL ${error.javaClass.simpleName}")
        }
    }
}

private data class CirclePixels(val nonBlack: Double, val mean: Double, val max: Double)

private fun circlePixels(bitmap: Bitmap): CirclePixels {
    val row = IntArray(bitmap.width)
    val radius = minOf(bitmap.width, bitmap.height) / 2.0
    var count = 0L
    var nonBlack = 0L
    var sum = 0.0
    var max = 0.0
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        val dy = y + 0.5 - bitmap.height / 2.0
        for (x in row.indices) {
            val dx = x + 0.5 - bitmap.width / 2.0
            if (dx * dx + dy * dy > radius * radius) continue
            val pixel = row[x]
            val alpha = (pixel ushr 24) / 255.0
            if (alpha > 0 && (pixel and 0xFFFFFF) != 0) nonBlack++
            val luminance = alpha * (
                0.2126 * ((pixel shr 16) and 255) +
                    0.7152 * ((pixel shr 8) and 255) +
                    0.0722 * (pixel and 255)
                )
            count++
            sum += luminance
            max = maxOf(max, luminance)
        }
    }
    return CirclePixels(nonBlack * 100.0 / count, sum / count, max)
}
