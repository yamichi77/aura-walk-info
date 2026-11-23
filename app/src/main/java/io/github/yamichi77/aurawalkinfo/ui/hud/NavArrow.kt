package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.heading.normalizeDegrees
import io.github.yamichi77.aurawalkinfo.nav.NavGuidance
import io.github.yamichi77.aurawalkinfo.nav.NavStep
import io.github.yamichi77.aurawalkinfo.nav.bearingTo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal object NavArrowStyle {
    const val widthDegrees = 3f
    const val gapDegrees = 0.35f
    const val tiltDegrees = 55.0
    const val strokeDp = 1.5f
    const val color = 0xFF7FDBFF
    const val noFixColor = 0xFF888780
    const val maxOpacity = 0.7f
    const val rotationMillis = 300
    const val projectionHalfSpan = 0.65f
    private const val halfThickness = 0.09
    private const val cameraDistance = 2.8

    private val outline = listOf(
        Point(0f, 0.62f), Point(0.48f, 0.08f), Point(0.18f, 0.08f),
        Point(0.18f, -0.52f), Point(-0.18f, -0.52f), Point(-0.18f, 0.08f), Point(-0.48f, 0.08f),
    )

    data class Point(val x: Float, val y: Float)
    data class Line(val from: Point, val to: Point)

    fun project(angleDegrees: Double, tilt: Double = tiltDegrees): List<Line> {
        val angle = Math.toRadians(angleDegrees)
        val pitch = Math.toRadians(tilt)
        val right = cos(angle)
        val turn = sin(angle)
        val rise = cos(pitch)
        val depth = sin(pitch)
        fun projectFace(height: Double) = outline.map { (x, forward) ->
            val horizontal = x * right + forward * turn
            val distance = forward * right - x * turn
            val perspective = cameraDistance / (cameraDistance + distance * depth - height * rise)
            Point(
                (horizontal * perspective).toFloat(),
                ((-distance * rise - height * depth) * perspective).toFloat(),
            )
        }
        val top = projectFace(halfThickness)
        val bottom = projectFace(-halfThickness)
        return buildList {
            for (index in outline.indices) {
                val next = (index + 1) % outline.size
                add(Line(top[index], top[next]))
                add(Line(bottom[index], bottom[next]))
                add(Line(top[index], bottom[index]))
            }
        }
    }
}

internal data class NavArrowInput(val travelBearing: Double, val gpsCourseDeg: Float?, val noFix: Boolean) {
    fun aim(headBearing: Double?): NavArrowAim? {
        if (headBearing != null) return NavArrowAim(NavArrowReference.HEAD, normalizeDegrees(travelBearing - headBearing))
        val gps = gpsCourseDeg?.takeUnless { noFix } ?: return null
        return NavArrowAim(NavArrowReference.GPS, normalizeDegrees(travelBearing - gps))
    }
}

internal enum class NavArrowReference { HEAD, GPS }
internal data class NavArrowAim(val reference: NavArrowReference, val angle: Double)

internal fun navArrowTravelBearing(guidance: NavGuidance): Double? {
    val turn = guidance.nextTurn ?: return guidance.destinationBearingDegrees
    if (turn.distanceMeters > 15.0) return turn.bearingDegrees
    return turn.step.outgoingBearing()
}

internal fun NavStep.outgoingBearing(): Double? =
    polyline.asSequence().zipWithNext().firstNotNullOfOrNull { (from, to) -> from.bearingTo(to) }
        ?: start.bearingTo(end)

internal fun shortestArrowTurn(from: Double, to: Double): Double = normalizeDegrees(to - from + 180) - 180

internal class NavArrowUpdates {
    private var lastAngleAt: Long? = null
    private var current: NavArrowAim? = null

    fun update(nowMillis: Long, aim: NavArrowAim?): NavArrowAim? {
        if (aim == null) {
            current = null
            return null
        }
        val previousAngle = current?.angle
        val last = lastAngleAt
        if (last == null || nowMillis - last >= intervalMillis &&
            (previousAngle == null || abs(shortestArrowTurn(previousAngle, aim.angle)) >= 2.0)
        ) {
            lastAngleAt = nowMillis
            current = aim
        } else if (previousAngle != null) {
            current = aim.copy(angle = previousAngle)
        }
        return current
    }

    companion object {
        const val intervalMillis = 100L
    }
}
