package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Point
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraPositionState
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
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import java.util.Locale
import kotlin.math.roundToInt
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
private const val CAMERA_ANIMATION_MS = 500

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
    routeProjection: ProjectNavPoints? = null,
    viewport: BigMapViewport? = null,
    snapCamera: Boolean = false,
    miniScale: Float = 1f,
    onMapLoaded: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val hasKey = rememberHasMapsKey()
    val selfCheck = debug.selfCheck && context.isDebugBuild()
    val large = bigMapMode != null
    val preview = (spec.destinationPrompt as? DestinationPrompt.Preview)?.route.takeIf { large }
    val currentCenter = spec.mapCenter ?: preview?.origin?.let { MapCenter(it.latitude, it.longitude) }
        ?: viewport?.center(null)?.takeIf { large }
    var lastCenter by remember { mutableStateOf<MapCenter?>(null) }
    if (currentCenter != null) SideEffect { lastCenter = currentCenter }
    val center = currentCenter ?: lastCenter
    val miniOpacity by animateFloatAsState(spec.mapOpacity, tween(HudAnimationMillis))
    val clusterOpacity by animateFloatAsState(spec.opacity, tween(HudAnimationMillis))
    val largeOpacity by animateFloatAsState(when (spec.destinationPrompt) {
        is DestinationPrompt.Candidates -> 0.4f
        is DestinationPrompt.Listening -> 0.3f
        else -> spec.opacity
    }, tween(HudAnimationMillis))
    val mapOpacity = if (large) largeOpacity else miniOpacity
    val markerOpacity = if (large) largeOpacity else clusterOpacity
    val scale = if (large) 1f else miniScale
    val route = spec.navigation?.route
    var project by remember { mutableStateOf<ProjectNavPoints?>(null) }
    if (selfCheck && !large) {
        LaunchedEffect(hasKey) {
            if (!hasKey) Log.i(MAP_LOG_TAG, "SKIP key=missing")
        }
        LaunchedEffect(spec.mapDiameter, spec.mapOpacity) {
            snapshotFlow { miniOpacity }.first { it == spec.mapOpacity }
            Log.i(MAP_LOG_TAG, "presentation diameter=${spec.mapDiameter} mapOpacity=${spec.mapOpacity}")
        }
    }

    BoxWithConstraints(modifier) {
        val diameter = minOf(maxWidth, maxHeight)
        if (center != null && hasKey) {
            val position = if (bigMapMode != null) {
                bigMapMode.cameraPosition(viewport?.center(center) ?: center, viewport?.zoom(bigMapMode) ?: bigMapMode.zoom)
            } else northUpCameraPosition(center, compensatedMapZoom(MiniMapZoom, miniScale))
            val camera = rememberCameraPositionState { this.position = position }
            if (selfCheck && !large) {
                LaunchedEffect(position) { Log.i(MAP_LOG_TAG, "camera targetBearing=${position.bearing}") }
            }
            MiniMapSurface(
                position, centeredMapPadding(diameter), selfCheck = selfCheck && !large,
                checkSnapshot = selfCheck && debug.fakeState != null && !large,
                modifier = Modifier.fillMaxSize().clip(CircleShape).alpha(mapOpacity),
                mapStyle = bigMapMode?.style ?: R.raw.map_style_dark,
                buildingsEnabled = bigMapMode?.buildingsEnabled == true,
                camera = camera, onRouteProjection = { project = it },
                viewport = viewport.takeIf { large }, preview = preview,
                moveImmediately = large || snapCamera,
                onMapLoaded = onMapLoaded,
            )
        }
        (routeProjection ?: project)?.let {
            if (preview != null) {
                RoutePreviewOverlay(preview, it, Modifier.matchParentSize().alpha(mapOpacity))
            } else if (route != null) {
                NavRouteOverlay(route, large, it, Modifier.matchParentSize().alpha(
                    if (spec.destinationPrompt is DestinationPrompt.Candidates) mapOpacity else 1f,
                ), scale)
            }
        }
        val markerHeight = arrowHeight.takeIf { large }
        MiniMapPlaceholder(
            spec, Modifier.fillMaxSize(), markerHeight, selfCheck && !large,
            markerOpacity, if (large) 0.4f else spec.mapLineOpacity, scale,
        ) { canvasCenter ->
            val projection = project
            when {
                preview != null -> null
                large && projection != null ->
                    spec.mapCenter?.let { projection(listOf(NavPoint(it.latitude, it.longitude))).single() }
                else -> canvasCenter
            }
        }
    }
}

@Composable
internal fun SharedMap(
    spec: HudVisualSpec.Clusters,
    debug: HudDebugOptions,
    presentation: SharedMapPresentation,
    visible: Boolean,
    open: Boolean,
    miniScale: Float,
    onTap: (HudTarget) -> Unit,
    modifier: Modifier,
    inputEnabled: Boolean,
    bigMapArrowHeight: Dp,
    bigMapMode: BigMapMode,
    viewport: BigMapViewport?,
    onMapPan: () -> Unit,
    onMapHold: (NavPoint) -> Unit,
    quantum: HudQuantum,
    quantumEnabled: Boolean = QuantumEffectsEnabled,
) {
    val large = presentation.large
    val transition = presentation.animation
    BoxWithConstraints(if (visible) modifier.testTag("shared-map") else modifier) {
        val diameter = minOf(maxHeight, maxWidth)
        val animation = transition ?: quantum.miniMap.takeUnless { large }
        val mapLayer = Modifier.matchParentSize().alpha(if (visible && !presentation.hidden) 1f else 0f)
        QuantumMap(animation, mapLayer, if (large) 1f else miniScale, quantumEnabled, large) { mapModifier ->
            MiniMapPanel(spec, if (visible) debug else debug.copy(selfCheck = false), mapModifier,
                bigMapMode.takeIf { large }, bigMapArrowHeight, viewport = viewport,
                snapCamera = presentation.hidden || transition?.effect == QuantumEffect.ReturnMap,
                miniScale = miniScale, onMapLoaded = quantum.onMapLoaded)
        }
        if (visible && open && presentation.acceptsLargeInput) {
            val onPan by rememberUpdatedState<(Offset) -> Unit> { delta ->
                if (spec.destinationPrompt !is DestinationPrompt.Preview) {
                    viewport?.pan(delta)
                    onMapPan()
                }
            }
            val onHold by rememberUpdatedState<(Offset) -> Unit> { pixel ->
                if (spec.destinationPrompt == DestinationPrompt.Map) {
                    viewport?.gestureMap?.pointAt(pixel)?.let(onMapHold)
                }
            }
            val onOutside by rememberUpdatedState { onTap(HudTarget.OUTSIDE) }
            val onMapTap by rememberUpdatedState {
                if (spec.destinationPrompt == DestinationPrompt.Map) onTap(HudTarget.BIGMAP)
            }
            Box(
                Modifier.matchParentSize().testTag("bigmap").then(
                    if (inputEnabled) Modifier.bigMapGestures({ onPan(it) }, { onHold(it) }, { onOutside() }, { onMapTap() })
                    else Modifier,
                ),
            )
            DestinationMapOverlay(spec.destinationPrompt, diameter, inputEnabled, onTap, quantumEnabled)
        } else if (visible && !open && presentation.settledMini && quantum.miniMap?.drawing != false) {
            Box(Modifier.matchParentSize().hudClick(HudTarget.MAP, onTap, inputEnabled))
        }
    }
}

// The SDK factory is also the lifetime boundary exercised by the host-side display tests.
internal val LocalHudMapViewFactory = staticCompositionLocalOf<(Context, GoogleMapOptions) -> MapView> {
    { context, options -> MapView(context, options) }
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
    camera: CameraPositionState = rememberCameraPositionState { this.position = position },
    onRouteProjection: ((ProjectNavPoints?) -> Unit)? = null,
    viewport: BigMapViewport? = null,
    preview: NavState.Preview? = null,
    moveImmediately: Boolean = false,
    onMapLoaded: (() -> Unit)? = null,
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
    val target by rememberUpdatedState(position)
    val previewCamera by rememberUpdatedState(preview?.let { it to position.tilt })
    val startedAt = remember { SystemClock.elapsedRealtime() }
    val loaded = remember { CompletableDeferred<Long>() }
    val sdkMap = remember { CompletableDeferred<SdkMap>() }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val captureSnapshot = checkSnapshot || probeCase != null
    val createView = LocalHudMapViewFactory.current
    GoogleMap(
        modifier = if (captureSnapshot || preview != null) modifier.onSizeChanged { mapSize = it } else modifier,
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().backgroundColor(android.graphics.Color.BLACK) },
        properties = properties,
        uiSettings = mapUi,
        contentPadding = padding,
        onMapLoaded = if (captureSnapshot || onMapLoaded != null) ({
            loaded.complete(SystemClock.elapsedRealtime() - startedAt)
            onMapLoaded?.invoke()
        }) else null,
        mapViewFactory = { viewContext, options ->
            createView(viewContext, options).apply {
                // AndroidView needs a native outline to clip its embedded map view.
                outlineProvider = mapOutline
                clipToOutline = true
            }
        },
    ) {
        if (onRouteProjection != null) NavMapProjectionEffect(camera, onRouteProjection)
        MapEffect(selfCheck, captureSnapshot, viewport, moveImmediately) { map ->
            if (captureSnapshot) sdkMap.complete(map)
            val gestureMap = if (viewport != null) SdkBigMapGestureMap(map) else null
            if (viewport != null) viewport.gestureMap = gestureMap
            // Finish each animation and use the latest pending fix if updates arrive faster than it.
            try {
                snapshotFlow {
                    val route = previewCamera
                    if (route == null) null to target else RoutePreviewCamera(route.first, route.second, mapSize) to null
                }.conflate().collect { (overview, next) ->
                    if (overview != null) {
                        overview.fit(map)
                    } else if (next != null && (moveImmediately ||
                            camera.position.zoom != next.zoom || camera.position.tilt != next.tilt)) {
                        if (map.cameraPosition != next) map.moveCamera(CameraUpdateFactory.newCameraPosition(next))
                    } else if (next != null && camera.position != next) {
                        try {
                            camera.animate(CameraUpdateFactory.newCameraPosition(next), CAMERA_ANIMATION_MS)
                        } catch (_: CancellationException) {
                            // SDK cancellation can interrupt one animation while the panel is still active.
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    if (selfCheck) Log.i(MAP_LOG_TAG, "camera bearing=${map.cameraPosition.bearing}")
                }
            } finally {
                if (viewport != null && viewport.gestureMap === gestureMap) viewport.gestureMap = null
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

private class SdkBigMapGestureMap(private val map: SdkMap) : BigMapGestureMap {
    override fun scrollBy(pixels: Offset): MapCenter {
        map.moveCamera(CameraUpdateFactory.scrollBy(pixels.x, pixels.y))
        val target = map.cameraPosition.target
        return MapCenter(target.latitude, target.longitude)
    }

    override fun pointAt(pixel: Offset): NavPoint? =
        map.projection.fromScreenLocation(Point(pixel.x.roundToInt(), pixel.y.roundToInt()))?.let {
            NavPoint(it.latitude, it.longitude)
        }
}

@Composable
internal fun rememberHasMapsKey(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.hasMapsKey() }
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
