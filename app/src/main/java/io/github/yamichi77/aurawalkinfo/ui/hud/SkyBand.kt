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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
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
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import io.github.yamichi77.aurawalkinfo.diagnostics.HudFrameStats
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
    frameStats: HudFrameStats?,
) {
    val latestTargets by rememberUpdatedState(targets)
    val latestOverride by rememberUpdatedState(settings.northOverride)
    val tracked = tracking && !settings.simulateTrackingLoss
    val enabled = settings.enabled
    val sampling = skyBandSamplingActive(fast, tracked, enabled, targets)
    val sample = remember(heading, sampling) { mutableStateOf<SkySample?>(null) }
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
            publish = { sample.value = it },
        )
    }
    val fallback = rememberUpdatedState(skyBandFrame(fast, tracked, enabled, null, null, targets))
    val frame = remember(sample) { derivedStateOf { sample.value?.frame ?: fallback.value } }
    if (debugBuild) LogSkyBand(frame, sample, sampling)
    SkyBandPanels(frame, frameStats) { slot ->
        SpatialSkyMark(session, slot, dpPerMeter, scale, selfCheck)
    }
}

internal data class VisibleSkyBand(val marks: List<SkyMark>, val alpha: Float)

@Composable
internal fun rememberSkyBandVisibility(frame: State<SkyBandFrame>): State<VisibleSkyBand?> {
    val latestFrame = rememberUpdatedState(frame)
    val status by remember { derivedStateOf { latestFrame.value.value.status } }
    val fast = status == SkyBandStatus.FAST
    // Reset the fade across FAST while keeping the spatial panel slots.
    var lastMarks by remember(fast) { mutableStateOf(emptyList<SkyMark>()) }
    val alpha = remember(fast) { Animatable(0f) }
    val showing = status == SkyBandStatus.SHOW
    LaunchedEffect(alpha, showing) {
        alpha.animateTo(if (showing) 1f else 0f, tween(SkyFadeMillis))
    }
    LaunchedEffect(alpha) {
        snapshotFlow { latestFrame.value.value.takeIf { it.status == SkyBandStatus.SHOW }?.marks }
            .collect { if (it != null) lastMarks = it }
    }
    return remember(alpha) {
        derivedStateOf {
            val current = latestFrame.value.value
            val marks = if (current.status == SkyBandStatus.SHOW) current.marks else lastMarks
            if (current.status == SkyBandStatus.FAST || alpha.value <= 0f || marks.isEmpty()) null
            else VisibleSkyBand(marks, SkyMaxOpacity * alpha.value)
        }
    }
}

private val SkyPanelSlots = listOf(
    SkyMarkKind.TURN, SkyMarkKind.DISTANCE, SkyMarkKind.DESTINATION, SkyMarkKind.SIGNAL,
)

internal class SkyBandSlot(
    val kind: SkyMarkKind,
    val mark: State<SkyMark?>,
    val alpha: () -> Float,
) {
    val layout = skyMarkLayout(kind)

    fun pose() = HudPlacement.pose(mark.value?.yaw ?: 0f, layout.pitch)
}

@Composable
@SubspaceComposable
internal fun SkyBandPanels(
    frame: State<SkyBandFrame>,
    frameStats: HudFrameStats? = null,
    placePanel: @Composable @SubspaceComposable (SkyBandSlot) -> Unit,
) {
    val latestBand = rememberUpdatedState(rememberSkyBandVisibility(frame))
    if (frameStats != null) {
        LaunchedEffect(frameStats) {
            try {
                snapshotFlow { latestBand.value.value?.marks?.size ?: 0 }
                    .collect { frameStats.visibleSky = it }
            } finally {
                frameStats.visibleSky = 0
            }
        }
    }
    var mounted by remember { mutableStateOf(false) }
    if (!mounted) {
        val showing by remember { derivedStateOf { latestBand.value.value != null } }
        if (!showing) return
        SideEffect { mounted = true }
    }
    val opacity = remember { { latestBand.value.value?.alpha ?: 0f } }
    for (kind in SkyPanelSlots) {
        val slot = remember(kind) {
            val mark = derivedStateOf(structuralEqualityPolicy()) {
                latestBand.value.value?.marks?.firstOrNull {
                    val layoutKind = when (it.kind) {
                        SkyMarkKind.EDGE_LEFT, SkyMarkKind.EDGE_RIGHT -> SkyMarkKind.TURN
                        else -> it.kind
                    }
                    layoutKind == kind
                }
            }
            SkyBandSlot(kind, mark, opacity)
        }
        placePanel(slot)
    }
}

@Composable
private fun LogSkyBand(frame: State<SkyBandFrame>, sample: State<SkySample?>, sampling: Boolean) {
    val status by remember(frame) { derivedStateOf { frame.value.status } }
    val kinds by remember(frame) { derivedStateOf { frame.value.marks.map { it.kind } } }
    LaunchedEffect(frame, status, kinds, sampling) {
        do {
            val latestFrame = frame.value
            val latestSample = sample.value
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
    slot: SkyBandSlot,
    dpPerMeter: Float,
    scale: Float,
    selfCheck: Boolean,
) {
    val layout = slot.layout
    val physicalDpPerMeter = dpPerMeter / scale
    val placement = remember(slot, dpPerMeter, scale) {
        skyPanelPlacement(dpPerMeter, scale, slot::pose)
    }
    val size = SubspaceModifier.width((layout.widthMeters * physicalDpPerMeter).dp)
        .height((layout.heightMeters * physicalDpPerMeter).dp)
    val shape = SpatialRoundedCornerShape(CornerSize(0.dp))
    MeasuredSkyPanel(session, slot, placement, size, shape, scale, selfCheck) {
        val artwork by remember(slot) {
            derivedStateOf { slot.mark.value?.copy(yaw = 0f) }
        }
        artwork?.let { SkyMarkContent(it, slot.alpha, layout.glyphMeters * physicalDpPerMeter, Modifier.fillMaxSize()) }
    }
}

@Composable
internal fun SkyMarkContent(mark: SkyMark, alpha: Float, glyphDp: Float, modifier: Modifier = Modifier) {
    SkyMarkContent(mark, { alpha }, glyphDp, modifier)
}

@Composable
private fun SkyMarkContent(mark: SkyMark, opacity: () -> Float, glyphDp: Float, modifier: Modifier) {
    val content = modifier.clipToBounds().graphicsLayer {
        alpha = opacity()
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
