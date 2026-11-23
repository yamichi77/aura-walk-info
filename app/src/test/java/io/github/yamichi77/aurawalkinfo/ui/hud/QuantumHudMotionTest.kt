package io.github.yamichi77.aurawalkinfo.ui.hud

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantumHudMotionTest {
    @Test
    fun particlesAreFiftyPercentLargerAndOpaqueOnlyDuringTheEffect() {
        for (fraction in listOf(0f, 0.5f, 0.95f)) {
            val style = checkNotNull(quantumParticleStyle(fraction, radiusDp = 2f))
            assertEquals(2f * 0.65f * 1.5f, style.radiusDp, 0.001f)
            assertEquals(1f, style.alpha, 0f)
        }
        assertNull(quantumParticleStyle(-1f, 2f))
        assertNull(quantumParticleStyle(1f, 2f))
        assertNull(quantumParticleStyle(1.2f, 2f))
    }

    @Test
    fun rimFlashStartsBrightAndEndsAfterTwoHundredMillis() {
        assertEquals(0f, quantumRimFlashStrength(-1f), 0f)
        assertEquals(1f, quantumRimFlashStrength(0f), 0f)
        assertEquals(0.5f, quantumRimFlashStrength(100f), 0f)
        assertEquals(0f, quantumRimFlashStrength(200f), 0f)
    }
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
