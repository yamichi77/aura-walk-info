package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import io.github.yamichi77.aurawalkinfo.R
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.Map3dSnapshotCheck
import io.github.yamichi77.aurawalkinfo.diagnostics.saveProbePng
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.maps.GoogleMap as SdkMap

internal const val MAP_LOG_TAG = "AuraMap"
private const val SNAPSHOT_FILE = "minimap-snapshot.png"
private const val MAP_ZOOM = 17.5f
private const val CAMERA_ANIMATION_MS = 500
private val bigMapModeHitFraction = HudPlacement.planeHalfSpan(1.5f) * 2 / HudPlacement.bigMapSizeMeters

internal fun centeredMapPadding(diameter: Dp) = PaddingValues(diameter * 0.15f)

internal fun northUpCameraPosition(center: MapCenter, zoom: Float) =
    CameraPosition(LatLng(center.latitude, center.longitude), zoom, 0f, 0f)

private val mapOutline = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val diameter = minOf(view.width, view.height)
        val left = (view.width - diameter) / 2
        val top = (view.height - diameter) / 2
        outline.setOval(left, top, left + diameter, top + diameter)
    }
}

private val mapUi = MapUiSettings(
    compassEnabled = false,
    indoorLevelPickerEnabled = false,
    mapToolbarEnabled = false,
    myLocationButtonEnabled = false,
    rotationGesturesEnabled = false,
    scrollGesturesEnabled = false,
    scrollGesturesEnabledDuringRotateOrZoom = false,
    tiltGesturesEnabled = false,
    zoomControlsEnabled = false,
    zoomGesturesEnabled = false,
)

@Composable
internal fun MiniMapPanel(
    spec: HudVisualSpec.Clusters,
    debug: HudDebugOptions,
    modifier: Modifier = Modifier,
    bigMapMode: BigMapMode? = null,
    arrowHeight: Dp? = null,
) {
    val context = LocalContext.current
    val hasKey = remember(context) { context.hasMapsKey() }
    val selfCheck = debug.selfCheck && context.isDebugBuild()
    val center = spec.mapCenter
    val mapOpacity by animateFloatAsState(spec.mapOpacity, tween(HudAnimationMillis))
    if (selfCheck) {
        LaunchedEffect(hasKey) {
            if (!hasKey) Log.i(MAP_LOG_TAG, "SKIP key=missing")
        }
        LaunchedEffect(spec.mapDiameter, spec.mapOpacity) {
            snapshotFlow { mapOpacity }.first { it == spec.mapOpacity }
            Log.i(MAP_LOG_TAG, "presentation diameter=${spec.mapDiameter} mapOpacity=${spec.mapOpacity}")
        }
    }

    BoxWithConstraints(modifier) {
        if (center != null && hasKey) {
            val position = bigMapMode?.cameraPosition(center) ?: northUpCameraPosition(center, MAP_ZOOM)
            if (selfCheck) {
                LaunchedEffect(position) {
                    Log.i(MAP_LOG_TAG, "camera targetBearing=${position.bearing}")
                }
            }
            // Maps projects the GPS target to the center of the padded area, including at a tilt.
            val padding = centeredMapPadding(minOf(maxWidth, maxHeight))
            MiniMapSurface(
                position, padding, selfCheck, checkSnapshot = selfCheck && debug.fakeState != null,
                modifier = Modifier.fillMaxSize().clip(CircleShape).alpha(mapOpacity),
                mapStyle = bigMapMode?.style ?: R.raw.map_style_dark,
                buildingsEnabled = bigMapMode?.buildingsEnabled == true,
            )
        }
        MiniMapPlaceholder(spec, Modifier.fillMaxSize(), arrowHeight, selfCheck)
    }
}

@Composable
internal fun ClickableMap(
    spec: HudVisualSpec.Clusters,
    debug: HudDebugOptions,
    target: HudTarget,
    onTap: (HudTarget) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bigMapArrowHeight: Dp? = null,
    bigMapMode: BigMapMode = BigMapMode.FLAT,
) {
    if (target == HudTarget.BIGMAP) {
        BoxWithConstraints(modifier) {
            val diameter = minOf(maxHeight, maxWidth)
            val opacity by animateFloatAsState(spec.opacity, tween(HudAnimationMillis))
            MiniMapPanel(
                spec = spec.copy(mapOpacity = spec.opacity, mapLineOpacity = 0.4f, mapDiameter = HudPlacement.bigMapDiameter),
                debug = debug.copy(selfCheck = false),
                modifier = Modifier.matchParentSize(),
                arrowHeight = bigMapArrowHeight,
                bigMapMode = bigMapMode,
            )
            Box(Modifier.matchParentSize().hudClick(target, onTap, enabled))
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = diameter * 0.01f)
                    .size(diameter * bigMapModeHitFraction)
                    .hudClick(HudTarget.BIGMAP_MODE, onTap, enabled),
                contentAlignment = Alignment.Center,
            ) {
                val labelSize = with(LocalDensity.current) { 14.dp.toSp() }
                Text(
                    bigMapMode.toggleLabel, Modifier.alpha(opacity), color = HudText,
                    fontSize = labelSize, lineHeight = labelSize, maxLines = 1,
                )
            }
        }
    } else {
        Box(modifier) {
            MiniMapPanel(spec, debug, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().hudClick(target, onTap, enabled))
        }
    }
}

@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun MiniMapSurface(
    position: CameraPosition,
    padding: PaddingValues,
    selfCheck: Boolean,
    checkSnapshot: Boolean,
    modifier: Modifier,
    @RawRes mapStyle: Int,
    buildingsEnabled: Boolean,
    probeCase: String? = null,
) {
    val context = LocalContext.current
    val properties = remember(context, mapStyle, buildingsEnabled) {
        MapProperties(
            isBuildingEnabled = buildingsEnabled,
            isIndoorEnabled = false,
            isMyLocationEnabled = false,
            mapStyleOptions = MapStyleOptions.loadRawResourceStyle(context, mapStyle),
        )
    }
    val camera = rememberCameraPositionState { this.position = position }
    val target by rememberUpdatedState(position)
    val startedAt = remember { SystemClock.elapsedRealtime() }
    val loaded = remember { CompletableDeferred<Long>() }
    val sdkMap = remember { CompletableDeferred<SdkMap>() }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val captureSnapshot = checkSnapshot || probeCase != null
    GoogleMap(
        modifier = if (captureSnapshot) modifier.onSizeChanged { mapSize = it } else modifier,
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().backgroundColor(android.graphics.Color.BLACK) },
        properties = properties,
        uiSettings = mapUi,
        contentPadding = padding,
        onMapLoaded = if (captureSnapshot) ({ loaded.complete(SystemClock.elapsedRealtime() - startedAt) }) else null,
        mapViewFactory = { viewContext, options ->
            MapView(viewContext, options).apply {
                // AndroidView needs a native outline to clip its embedded map view.
                outlineProvider = mapOutline
                clipToOutline = true
            }
        },
    ) {
        MapEffect(selfCheck, captureSnapshot) { map ->
            if (captureSnapshot) sdkMap.complete(map)
            // Finish each animation and use the latest pending fix if updates arrive faster than it.
            snapshotFlow { target }.conflate().collect { next ->
                if (camera.position != next) {
                    try {
                        camera.animate(CameraUpdateFactory.newCameraPosition(next), CAMERA_ANIMATION_MS)
                    } catch (_: CancellationException) {
                        // SDK cancellation can interrupt one animation while the panel is still active.
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (selfCheck) Log.i(MAP_LOG_TAG, "camera bearing=${map.cameraPosition.bearing}")
            }
        }
    }
    if (probeCase != null) {
        Map3dSnapshotCheck(probeCase, position, loaded, sdkMap) { mapSize }
    } else if (checkSnapshot) {
        LaunchedEffect(sdkMap, loaded) {
            try {
                val map = withTimeoutOrNull(20_000L) {
                    loaded.await()
                    sdkMap.await()
                }
                if (map == null) {
                    Log.i(MAP_LOG_TAG, "FAIL onMapLoaded=${loaded.isCompleted} timeout")
                } else {
                    checkMapSnapshot(context, map, mapSize)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.i(MAP_LOG_TAG, "FAIL onMapLoaded=${loaded.isCompleted} ${error.javaClass.simpleName}")
            }
        }
    }
}

internal fun Context.hasMapsKey(): Boolean {
    val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    val key = info.metaData?.getString("com.google.android.geo.API_KEY")
    return !key.isNullOrBlank() && key != "DEFAULT_API_KEY"
}

private suspend fun checkMapSnapshot(context: Context, map: SdkMap, size: IntSize) {
    // T2 observed an SDK crash when snapshot was requested on the sleeping 0x0 surface.
    val interactive = context.getSystemService(PowerManager::class.java).isInteractive
    if (!interactive || size.width <= 0 || size.height <= 0) {
        Log.i(MAP_LOG_TAG, "FAIL onMapLoaded=true snapshot skipped interactive=$interactive layout=${size.width}x${size.height}")
        return
    }
    val result = CompletableDeferred<Bitmap?>()
    val bearing = map.cameraPosition.bearing
    map.snapshot { result.complete(it) }
    val bitmap = withTimeoutOrNull(5_000L) { result.await() }
    if (bitmap == null) {
        Log.i(MAP_LOG_TAG, "FAIL onMapLoaded=true snapshot unavailable bearing=$bearing")
        return
    }
    val (nonBlack, bright) = withContext(Dispatchers.Default) { pixelPercentages(bitmap) }
    saveProbePng(context, SNAPSHOT_FILE, bitmap)
    val verdict = if (nonBlack >= 1.0 && bright <= 25.0) "PASS" else "FAIL"
    Log.i(MAP_LOG_TAG, String.format(
        Locale.ROOT, "%s onMapLoaded=true nonBlack=%.2f%% bright=%.2f%% bearing=%.2f snapshot=%s",
        verdict, nonBlack, bright, bearing, SNAPSHOT_FILE,
    ))
}

private fun pixelPercentages(bitmap: Bitmap): Pair<Double, Double> {
    val row = IntArray(bitmap.width)
    var nonBlack = 0L
    var bright = 0L
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (pixel in row) {
            val rgb = pixel and 0xFFFFFF
            val alpha = (pixel ushr 24) / 255.0
            if (alpha > 0.0 && rgb != 0) nonBlack++
            val luminance = alpha * (
                0.2126 * ((rgb shr 16) and 255) +
                    0.7152 * ((rgb shr 8) and 255) +
                    0.0722 * (rgb and 255)
                )
            if (luminance >= 127.5) bright++
        }
    }
    val total = bitmap.width.toLong() * bitmap.height
    return nonBlack * 100.0 / total to bright * 100.0 / total
}
