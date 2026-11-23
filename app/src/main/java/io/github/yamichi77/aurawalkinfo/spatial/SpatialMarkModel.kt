package io.github.yamichi77.aurawalkinfo.spatial

import io.github.yamichi77.aurawalkinfo.heading.HeadingAxes
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.bearingTo
import io.github.yamichi77.aurawalkinfo.nav.distanceTo
import io.github.yamichi77.aurawalkinfo.pics.MAX_PICS_LIGHTS
import io.github.yamichi77.aurawalkinfo.pics.PicsCandidate
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.ui.hud.outgoingBearing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class MarkVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: MarkVector) = MarkVector(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: MarkVector) = MarkVector(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Double) = MarkVector(x * factor, y * factor, z * factor)
    fun dot(other: MarkVector) = x * other.x + y * other.y + z * other.z
    fun cross(other: MarkVector) = MarkVector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun length() = sqrt(dot(this))
    fun toward(other: MarkVector, fraction: Double) = this + (other - this) * fraction
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val UP = MarkVector(0.0, 1.0, 0.0)
    }
}

internal data class SpatialFix(val point: NavPoint, val eye: MarkVector, val northOffsetDegrees: Double)

/** Returns positions in ARCore perception space. Renderers can bind these to a local anchor. */
internal fun interface SpatialProjector {
    fun project(fix: SpatialFix, target: NavPoint, heightAboveGroundMeters: Double): MarkVector
}

internal object GpsSpatialProjector : SpatialProjector {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val EYE_HEIGHT_METERS = 1.6

    override fun project(fix: SpatialFix, target: NavPoint, heightAboveGroundMeters: Double): MarkVector {
        val longitudeDelta = (target.longitude - fix.point.longitude + 540.0) % 360.0 - 180.0
        val east = EARTH_RADIUS_METERS * Math.toRadians(longitudeDelta) *
            cos(Math.toRadians((target.latitude + fix.point.latitude) / 2))
        val north = EARTH_RADIUS_METERS * Math.toRadians(target.latitude - fix.point.latitude)
        val bearing = Math.toDegrees(atan2(east, north))
        return fix.eye + directionInPerception(bearing, fix.northOffsetDegrees) * hypot(east, north) +
            MarkVector.UP * (heightAboveGroundMeters - EYE_HEIGHT_METERS)
    }
}

private fun directionInPerception(bearing: Double, northOffset: Double): MarkVector {
    val angle = Math.toRadians(bearing - northOffset)
    return MarkVector(HeadingAxes.RIGHT_X * sin(angle), 0.0, HeadingAxes.FORWARD_Z * cos(angle))
}

internal enum class MarkKind(val baseHeight: Double, val strokeCounts: List<Int>) {
    TURN(7.0, listOf(21)),
    DESTINATION(8.0, listOf(17)),
    SIGNAL(7.0, listOf(16, MAX_PICS_LIGHTS * 8, MAX_PICS_LIGHTS * 8, MAX_PICS_LIGHTS * 8)),
}

internal enum class MarkReason {
    SHOW, FAST, DISABLED, TRACKING_LOST, NO_NORTH, UNCERTAIN, NO_FIX, GPS_ACCURACY,
    NO_GUIDANCE, NO_TURN, TURN_TOO_CLOSE, OUTGOING_UNKNOWN, DESTINATION_TOO_FAR,
    NO_SIGNAL, BELOW_ELEVATION, ANCHOR_UNAVAILABLE, RENDER_UNAVAILABLE,
}

internal data class MarkTarget(val point: NavPoint, val bearing: Double? = null, val signal: PicsCandidate? = null)
internal data class MarkDecision(val reason: MarkReason, val target: MarkTarget? = null)

internal fun spatialBlocker(
    motion: MotionState,
    enabled: Boolean,
    tracking: Boolean,
    north: NorthEstimate?,
    point: NavPoint?,
    accuracy: Float?,
): MarkReason? = when {
    motion == MotionState.FAST -> MarkReason.FAST
    !enabled -> MarkReason.DISABLED
    !tracking -> MarkReason.TRACKING_LOST
    north == null || !north.offsetDegrees.isFinite() -> MarkReason.NO_NORTH
    !north.uncertaintyDegrees.isFinite() || north.uncertaintyDegrees !in 0.0..15.0 -> MarkReason.UNCERTAIN
    accuracy != null && accuracy !in 0f..20f -> MarkReason.GPS_ACCURACY
    motion == MotionState.NO_FIX || point == null || accuracy == null -> MarkReason.NO_FIX
    else -> null
}

internal fun decideMark(
    kind: MarkKind,
    current: NavPoint,
    nav: NavState,
    signal: PicsCandidate?,
    motion: MotionState,
): MarkDecision {
    val guiding = when (nav) {
        is NavState.Guiding -> nav
        is NavState.Rerouting -> nav.previous
        else -> null
    }
    return when (kind) {
        MarkKind.TURN -> {
            val turn = (guiding ?: return MarkDecision(MarkReason.NO_GUIDANCE)).guidance.nextTurn
                ?: return MarkDecision(MarkReason.NO_TURN)
            if (turnTooClose(current.distanceTo(turn.step.start))) return MarkDecision(MarkReason.TURN_TOO_CLOSE)
            val bearing = turn.step.outgoingBearing() ?: return MarkDecision(MarkReason.OUTGOING_UNKNOWN)
            MarkDecision(MarkReason.SHOW, MarkTarget(turn.step.start, bearing))
        }
        MarkKind.DESTINATION -> {
            val destination = (guiding ?: return MarkDecision(MarkReason.NO_GUIDANCE)).destination.point
            if (destinationTooFar(current.distanceTo(destination))) MarkDecision(MarkReason.DESTINATION_TOO_FAR)
            else MarkDecision(MarkReason.SHOW, MarkTarget(destination))
        }
        MarkKind.SIGNAL -> {
            // T17a owns the 30 m limit and packet expiry.
            if (motion != MotionState.STOPPED || signal == null) return MarkDecision(MarkReason.NO_SIGNAL)
            val intersection = NavPoint(signal.position.latitude, signal.position.longitude)
            MarkDecision(MarkReason.SHOW, MarkTarget(intersection, current.bearingTo(intersection), signal))
        }
    }
}

internal fun turnTooClose(distanceMeters: Double) = distanceMeters <= 15.0
internal fun destinationTooFar(distanceMeters: Double) = distanceMeters > 150.0

internal fun markForward(target: MarkTarget, northOffset: Double) =
    directionInPerception(target.bearing ?: 0.0, northOffset)

internal const val MARK_POSITION_INTERVAL_MS = 500L
internal const val MARK_FADE_MS = 300L
internal const val MARK_MAX_OPACITY = 0.5f

internal enum class SpatialUpdateMode(val hideReason: MarkReason?) {
    ACTIVE(null), NO_TARGETS(null), DISABLED(MarkReason.DISABLED), FAST(MarkReason.FAST),
}

internal fun spatialUpdateMode(hasTargets: Boolean, enabled: Boolean, motion: MotionState) = when {
    motion == MotionState.FAST -> SpatialUpdateMode.FAST
    !enabled -> SpatialUpdateMode.DISABLED
    !hasTargets -> SpatialUpdateMode.NO_TARGETS
    else -> SpatialUpdateMode.ACTIVE
}

private object SpatialUpdateInterval {
    const val VISIBLE_MS = 100L // 10 Hz
    const val FADE_MS = 33L // About 30 Hz
}

internal fun spatialUpdateIntervalMillis(mode: SpatialUpdateMode, fading: Boolean): Long? = when {
    mode == SpatialUpdateMode.FAST -> null
    fading -> SpatialUpdateInterval.FADE_MS
    mode == SpatialUpdateMode.ACTIVE -> SpatialUpdateInterval.VISIBLE_MS
    else -> null
}

internal class SmoothedMarkPosition(initial: MarkVector) {
    private var from = initial
    private var target = initial
    private var startedAt = 0L

    fun retarget(position: MarkVector, now: Long) {
        if ((position - target).length() < 1.0) return
        from = at(now)
        target = position
        startedAt = now
    }

    fun at(now: Long): MarkVector {
        val fraction = ((now - startedAt) / 1_000.0).coerceIn(0.0, 1.0)
        return from.toward(target, fraction * fraction * (3 - 2 * fraction))
    }
}

internal class MarkFade {
    private var shown = false
    private var from = 0f
    private var startedAt = 0L
    var isAnimating = false
        private set

    fun update(show: Boolean, immediate: Boolean, now: Long): Float {
        if (immediate) {
            shown = false
            from = 0f
        } else if (show != shown) {
            from = value(now)
            startedAt = now
            shown = show
        }
        isAnimating = from != (if (shown) MARK_MAX_OPACITY else 0f) && now - startedAt < MARK_FADE_MS
        return value(now)
    }

    private fun value(now: Long): Float {
        val fraction = ((now - startedAt).toFloat() / MARK_FADE_MS).coerceIn(0f, 1f)
        return from + ((if (shown) MARK_MAX_OPACITY else 0f) - from) * fraction
    }
}

internal const val MIN_MARK_ELEVATION_DEGREES = 5.0
private val MIN_ELEVATION_SLOPE = tan(Math.toRadians(MIN_MARK_ELEVATION_DEGREES))

internal data class MarkView(val eye: MarkVector, private val up: MarkVector) {
    private fun horizontal(delta: MarkVector, rise: Double) = sqrt((delta.dot(delta) - rise * rise).coerceAtLeast(0.0))

    fun elevation(point: MarkVector): Double {
        val delta = point - eye
        val rise = delta.dot(up)
        return Math.toDegrees(atan2(rise, horizontal(delta, rise)))
    }

    fun margin(point: MarkVector): Double {
        val delta = point - eye
        val rise = delta.dot(up)
        return rise - horizontal(delta, rise) * MIN_ELEVATION_SLOPE
    }
}
