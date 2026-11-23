package io.github.yamichi77.aurawalkinfo.ui.hud

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantumHudMotionTest {
    @Test
    fun incomingPointsReachTheirTargetBeforeTheContentFadesIn() {
        val target = QuantumPoint(0.6f, -0.8f)
        val particle = QuantumParticle(target, Random(13))
        fun error(fraction: Float): Float {
            val frame = quantumTransitionFrame(fraction, outgoing = false)
            return hypot(particle.x(frame) - target.x, particle.y(frame) - target.y)
        }
        assertTrue(error(0.35f) < error(0f))
        assertTrue(error(0.7f) < 0.005f)
        assertTrue(error(0.85f) < 0.005f)
        assertEquals(0f, quantumContentAlpha(0.7f), 0f)
        assertEquals(0.5f, quantumContentAlpha(0.85f), 0.001f)
        assertEquals(1f, quantumContentAlpha(1f), 0f)
    }

    @Test
    fun outgoingPointsDisperseFromTheMapRim() {
        val particle = QuantumParticle(QuantumPoint(0.6f, 0.8f), Random(13))
        fun radius(fraction: Float): Float {
            val frame = quantumTransitionFrame(fraction, outgoing = true)
            return hypot(particle.x(frame), particle.y(frame))
        }
        assertEquals(1f, radius(0f), 0.005f)
        assertTrue(radius(0.5f) > radius(0f))
        assertTrue(radius(1f) > radius(0.5f))
    }
}
