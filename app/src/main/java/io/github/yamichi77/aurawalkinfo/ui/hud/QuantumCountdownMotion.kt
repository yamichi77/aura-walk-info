package io.github.yamichi77.aurawalkinfo.ui.hud

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal const val COUNTDOWN_PARTICLE_COUNT = 100
internal const val COUNTDOWN_SECOND_MILLIS = 1_000f
internal const val COUNTDOWN_RIPPLE_MILLIS = 300

// Coordinates are centered on the glyph, in units of its height.
internal data class CountdownPoint(val x: Float, val y: Float)

internal class CountdownParticle(target: CountdownPoint, random: Random) {
    private val angle = random.nextFloat() * (2 * PI).toFloat()
    private val cloudRadius = 0.25f + 0.63f * random.nextFloat()
    private val cloudX = cloudRadius * cos(angle)
    private val cloudY = cloudRadius * sin(angle)
    private val drift = 0.45f + 0.2f * random.nextFloat()
    private val phase = random.nextFloat() * (2 * PI).toFloat()

    val targetX = target.x
    val targetY = target.y
    val radiusDp = 1f + random.nextFloat()

    fun x(frame: QuantumCountdownFrame): Float = coordinate(
        targetX, cloudX, 0.003f * sin(frame.jitterPhase + phase), frame,
    )

    fun y(frame: QuantumCountdownFrame): Float = coordinate(
        targetY, cloudY, 0.003f * cos(frame.jitterPhase + phase), frame,
    )

    private fun coordinate(
        target: Float, cloud: Float, jitter: Float, frame: QuantumCountdownFrame,
    ): Float = cloud + (target + jitter - cloud) * frame.convergence + target * drift * frame.dispersal
}

internal class QuantumCountdownFrame(elapsedMillis: Float) {
    private val elapsed = elapsedMillis.coerceIn(0f, COUNTDOWN_SECOND_MILLIS)
    private val gathering = (elapsed / 400f).coerceAtMost(1f)
    val convergence = gathering * gathering * (3f - 2f * gathering)
    val dispersal = ((elapsed - 800f) / 200f).coerceAtLeast(0f)
    val jitterPhase = elapsed * 0.024f
    val particleAlpha = (0.25f + 0.65f * convergence) * (1f - dispersal)
    val correctOutlineAlpha = (0.22f + 0.58f * convergence) * (1f - dispersal)
    val otherOutlineAlpha = 0.26f * (1f - gathering)
    val electronAngle = ((elapsed / COUNTDOWN_SECOND_MILLIS - 0.25f) * 2 * PI).toFloat()
}
