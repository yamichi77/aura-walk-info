package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.heading.normalizeDegrees
import io.github.yamichi77.aurawalkinfo.nav.NavGuidance
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.delay

private const val VisibleYaw = 27.0
private const val EdgeYaw = 28f
private const val MaxUncertainty = 15.0
private const val SkySampleMillis = 67L
private const val MinHeadingChangeDegrees = 0.5

private val NorthEstimate.isUsable: Boolean get() = uncertaintyDegrees in 0.0..MaxUncertainty

internal fun relativeSkyYaw(bearing: Double, headBearing: Double): Double =
    normalizeDegrees(bearing - headBearing + 180.0) - 180.0

internal class SkyHeadingSmoother {
    private var previous: Double? = null
    private var lastMillis = 0L

    fun update(bearing: Double?, nowMillis: Long): Double? {
        val last = previous
        val smoothed = if (bearing == null || last == null) bearing else {
            val fraction = 1 - exp(-(nowMillis - lastMillis).coerceAtLeast(0L) / 150.0)
            normalizeDegrees(last + relativeSkyYaw(bearing, last) * fraction)
        }
        previous = smoothed
        lastMillis = nowMillis
        return smoothed
    }
}

internal data class SkyBandTargets(
    val guidance: NavGuidance?,
    val intersection: PicsCandidate?,
) {
    val hasTargets: Boolean get() = guidance != null || intersection != null

    companion object {
        fun from(nav: NavState, pics: PicsCandidate?) = SkyBandTargets(
            when (nav) {
                is NavState.Guiding -> nav.guidance
                is NavState.Rerouting -> nav.previous.guidance
                else -> null
            },
            pics,
        )
    }
}

internal fun skyBandSamplingActive(fast: Boolean, tracking: Boolean, enabled: Boolean, targets: SkyBandTargets) =
    !fast && tracking && enabled && targets.hasTargets

internal data class SkySample(
    val targets: SkyBandTargets,
    val north: NorthEstimate?,
    val bearing: Double?,
    val rawBearing: Double? = bearing,
) {
    val frame by lazy(LazyThreadSafetyMode.NONE) {
        skyBandFrame(fast = false, tracking = true, enabled = true, north, bearing, targets)
    }

    fun changedSince(previous: SkySample): Boolean {
        // The nullable quality distinguishes missing, usable and uncertain estimates.
        if (targets != previous.targets || north?.isUsable != previous.north?.isUsable) return true

        val lastBearing = previous.bearing
        if (bearing == lastBearing) return false
        if (bearing == null || lastBearing == null ||
            abs(relativeSkyYaw(bearing, lastBearing)) >= MinHeadingChangeDegrees
        ) return true

        // A mark can cross the field-of-view boundary within the heading deadband.
        return frame.status != previous.frame.status || frame.marks.size != previous.frame.marks.size ||
            frame.marks.indices.any { frame.marks[it].kind != previous.frame.marks[it].kind }
    }
}

internal suspend fun sampleSkyBand(
    active: Boolean,
    read: (SkyHeadingSmoother) -> SkySample,
    publish: (SkySample) -> Unit,
) {
    if (!active) return
    val smoother = SkyHeadingSmoother()
    var published: SkySample? = null
    while (true) {
        val next = read(smoother)
        if (published == null || next.changedSince(published)) {
            published = next
            publish(next)
        }
        delay(SkySampleMillis)
    }
}

internal enum class SkyBandStatus {
    SHOW, FAST, TRACKING_LOST, NO_ESTIMATE, UNCERTAIN, DISABLED, NO_TARGETS, OUT_OF_VIEW,
}

internal enum class SkyMarkKind { TURN, EDGE_LEFT, EDGE_RIGHT, DISTANCE, DESTINATION, SIGNAL }

internal data class SkyMark(
    val kind: SkyMarkKind,
    val yaw: Float,
    val distance: String? = null,
    val signal: SkySignalMark? = null,
)

internal data class SkyBandFrame(val status: SkyBandStatus, val marks: List<SkyMark> = emptyList())

internal fun skyBandFrame(
    fast: Boolean,
    tracking: Boolean,
    enabled: Boolean,
    north: NorthEstimate?,
    headBearing: Double?,
    targets: SkyBandTargets,
): SkyBandFrame {
    val hidden = when {
        fast -> SkyBandStatus.FAST
        !tracking -> SkyBandStatus.TRACKING_LOST
        !enabled -> SkyBandStatus.DISABLED
        !targets.hasTargets -> SkyBandStatus.NO_TARGETS
        north == null -> SkyBandStatus.NO_ESTIMATE
        !north.isUsable -> SkyBandStatus.UNCERTAIN
        headBearing == null || !headBearing.isFinite() -> SkyBandStatus.NO_ESTIMATE
        else -> null
    }
    if (hidden != null) return SkyBandFrame(hidden)
    val head = checkNotNull(headBearing)

    val guidance = targets.guidance
    val turn = guidance?.nextTurn
    val turnBearing = turn?.bearingDegrees
    val destination = guidance?.destinationBearingDegrees
    val marks = buildList {
        if (turn != null && turnBearing != null) {
            val yaw = relativeSkyYaw(turnBearing, head)
            when {
                yaw < -VisibleYaw -> add(SkyMark(SkyMarkKind.EDGE_LEFT, -EdgeYaw))
                yaw > VisibleYaw -> add(SkyMark(SkyMarkKind.EDGE_RIGHT, EdgeYaw))
                else -> {
                    add(SkyMark(SkyMarkKind.TURN, yaw.toFloat()))
                    add(SkyMark(SkyMarkKind.DISTANCE, yaw.toFloat(), roundedNavTurnDistance(turn.distanceMeters)))
                }
            }
        }
        if (destination != null && (turnBearing == null || abs(relativeSkyYaw(destination, turnBearing)) >= 3.0)) {
            val yaw = relativeSkyYaw(destination, head)
            if (abs(yaw) <= VisibleYaw) add(SkyMark(SkyMarkKind.DESTINATION, yaw.toFloat()))
        }
        targets.intersection?.let { intersection ->
            val yaw = relativeSkyYaw(intersection.bearingDeg, head)
            if (abs(yaw) <= VisibleYaw) {
                add(SkyMark(SkyMarkKind.SIGNAL, yaw.toFloat(), signal = intersection.toSkySignalMark()))
            }
        }
    }
    return if (marks.isEmpty()) SkyBandFrame(SkyBandStatus.OUT_OF_VIEW) else SkyBandFrame(SkyBandStatus.SHOW, marks)
}
