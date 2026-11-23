package io.github.yamichi77.aurawalkinfo.ui.hud

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.heading.HeadTracking
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.pics.PicsFreshness
import java.util.Locale
import kotlinx.coroutines.delay

private const val SkyFadeMillis = 300
private const val SkyMaxOpacity = 0.5f
private val SkyCyan = Color(0xFF7FDBFF)

@Composable
@SubspaceComposable
internal fun SpatialSkyBand(
    session: Session,
    targets: SkyBandTargets,
    settings: SkyBandSettings,
    heading: HeadingTracking,
    tracking: Boolean,
    fast: Boolean,
    dpPerMeter: Float,
    scale: Float,
    debugBuild: Boolean,
    selfCheck: Boolean,
) {
    val latestTargets by rememberUpdatedState(targets)
    val latestOverride by rememberUpdatedState(settings.northOverride)
    val tracked = tracking && !settings.simulateTrackingLoss
    val enabled = settings.enabled
    val sampling = skyBandSamplingActive(fast, tracked, enabled, targets)
    var sample by remember(heading, sampling) { mutableStateOf<SkySample?>(null) }
    LaunchedEffect(heading, sampling) {
        sampleSkyBand(
            active = sampling,
            read = { smoother ->
                val now = SystemClock.elapsedRealtime()
                val north = latestOverride ?: heading.displayNorth(now)
                val pose = heading.poseAt(now)?.takeIf { it.tracking == HeadTracking.TRACKING }
                val rawBearing = pose?.yawDegrees?.let { north?.bearingForYaw(it) }
                SkySample(latestTargets, north, smoother.update(rawBearing, now), rawBearing)
            },
            publish = { sample = it },
        )
    }
    val frame = sample?.frame ?: skyBandFrame(fast, tracked, enabled, null, null, targets)
    if (debugBuild) LogSkyBand(frame, sample, sampling)
    val visible = rememberSkyBandVisibility(frame) ?: return
    visible.marks.forEach { mark ->
        key(mark.kind) {
            SpatialSkyMark(session, mark, visible.alpha, dpPerMeter, scale, selfCheck)
        }
    }
}

internal data class VisibleSkyBand(val marks: List<SkyMark>, val alpha: Float)

@Composable
internal fun rememberSkyBandVisibility(frame: SkyBandFrame): VisibleSkyBand? {
    // Dropping the animation state also prevents a pre-FAST frame from resurfacing.
    if (frame.status == SkyBandStatus.FAST) return null
    var lastMarks by remember { mutableStateOf(emptyList<SkyMark>()) }
    val alpha = remember { Animatable(0f) }
    val showing = frame.status == SkyBandStatus.SHOW
    LaunchedEffect(showing) {
        alpha.animateTo(if (showing) 1f else 0f, tween(SkyFadeMillis))
    }
    SideEffect {
        if (showing || alpha.value == 0f) lastMarks = frame.marks
    }
    val marks = if (showing) frame.marks else lastMarks
    return if (alpha.value > 0f && marks.isNotEmpty()) VisibleSkyBand(marks, SkyMaxOpacity * alpha.value) else null
}

@Composable
private fun LogSkyBand(frame: SkyBandFrame, sample: SkySample?, sampling: Boolean) {
    val latestFrame by rememberUpdatedState(frame)
    val latestSample by rememberUpdatedState(sample)
    LaunchedEffect(frame.status, frame.marks.map { it.kind }, sampling) {
        do {
            val angles = latestFrame.marks.joinToString(",") { "${it.kind}:${skyDegrees(it.yaw.toDouble())}" }
            Log.i("AuraSky", "${latestFrame.status} head=${skyDegrees(latestSample?.bearing)} " +
                "rawHead=${skyDegrees(latestSample?.rawBearing)} " +
                "uncertainty=${skyDegrees(latestSample?.north?.uncertaintyDegrees)} marks=${angles.ifEmpty { "-" }}")
            if (!sampling) break
            delay(1_000)
        } while (true)
    }
}

internal fun skyDegrees(angle: Double?): String = angle?.let { "%.2f".format(Locale.ROOT, it) } ?: "-"

@Composable
@SubspaceComposable
private fun SpatialSkyMark(
    session: Session,
    mark: SkyMark,
    alpha: Float,
    dpPerMeter: Float,
    scale: Float,
    selfCheck: Boolean,
) {
    val layout = skyMarkLayout(mark.kind)
    val physicalDpPerMeter = dpPerMeter / scale
    val pose = HudPlacement.pose(mark.yaw, layout.pitch)
    val placement = HudPlacement.modifier(pose, dpPerMeter, scale)
    val size = SubspaceModifier.width((layout.widthMeters * physicalDpPerMeter).dp)
        .height((layout.heightMeters * physicalDpPerMeter).dp)
    val shape = SpatialRoundedCornerShape(CornerSize(0.dp))
    val content: @Composable () -> Unit = {
        SkyMarkContent(mark, alpha, layout.glyphMeters * physicalDpPerMeter, Modifier.fillMaxSize())
    }
    if (selfCheck) {
        MeasuredSkyPanel(session, mark.kind, placement, size, shape, scale, content)
    } else {
        SpatialPanel(placement.then(size), shape = shape, content = content)
    }
}

@Composable
internal fun SkyMarkContent(mark: SkyMark, alpha: Float, glyphDp: Float, modifier: Modifier = Modifier) {
    val content = modifier.clipToBounds().graphicsLayer {
        this.alpha = alpha
        compositingStrategy = CompositingStrategy.Offscreen
    }.testTag("sky-${mark.kind.name}")
    if (mark.kind == SkyMarkKind.DISTANCE) {
        Box(content, contentAlignment = Alignment.Center) {
            HudLabel(requireNotNull(mark.distance), with(LocalDensity.current) { 14.dp.toSp().value }, SkyCyan)
        }
        return
    }
    Canvas(content) {
        val radius = glyphDp.dp.toPx() / 2f
        val stroke = Stroke(width = 1.dp.toPx().coerceAtMost(radius * 0.3f))
        when (mark.kind) {
            SkyMarkKind.DESTINATION, SkyMarkKind.SIGNAL -> {
                val ringColor = if (mark.signal?.freshness == PicsFreshness.STALE) {
                    PicsStaleColor
                } else SkyCyan
                drawCircle(ringColor, radius, style = stroke)
                mark.signal?.let { drawPicsDots(it, center, 2 * radius) }
            }
            SkyMarkKind.TURN, SkyMarkKind.EDGE_LEFT, SkyMarkKind.EDGE_RIGHT -> {
                val path = Path()
                if (mark.kind == SkyMarkKind.TURN) {
                    path.moveTo(center.x - radius, center.y - radius / 2)
                    path.lineTo(center.x + radius, center.y - radius / 2)
                    path.lineTo(center.x, center.y + radius / 2)
                    path.close()
                } else {
                    val direction = if (mark.kind == SkyMarkKind.EDGE_LEFT) -1 else 1
                    val offset = direction * radius / 2
                    path.moveTo(center.x - offset, center.y - radius)
                    path.lineTo(center.x + offset, center.y)
                    path.lineTo(center.x - offset, center.y + radius)
                }
                drawPath(path, SkyCyan, style = stroke)
            }
            SkyMarkKind.DISTANCE -> Unit
        }
    }
}
