package io.github.yamichi77.aurawalkinfo.ui.hud

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantumCountdownMotionTest {
    @Test
    fun seededParticlesGatherJitterAndMoveOutwardBeforeFading() {
        val target = CountdownPoint(0.23f, -0.42f)
        val particle = CountdownParticle(target, Random(11))
        val repeat = CountdownParticle(target, Random(11))
        val cloud = QuantumCountdownFrame(0f)
        val gathering = QuantumCountdownFrame(200f)
        val gathered = QuantumCountdownFrame(400f)
        val settled = QuantumCountdownFrame(600f)
        val dispersing = QuantumCountdownFrame(900f)
        val gone = QuantumCountdownFrame(1_000f)
        fun error(frame: QuantumCountdownFrame) =
            hypot(particle.x(frame) - target.x, particle.y(frame) - target.y)
        fun distance(frame: QuantumCountdownFrame) = hypot(particle.x(frame), particle.y(frame))

        assertEquals(particle.x(cloud), repeat.x(cloud), 0f)
        assertEquals(particle.y(cloud), repeat.y(cloud), 0f)
        assertTrue(error(gathering) < error(cloud))
        assertTrue(error(gathered) < 0.005f)
        assertTrue(error(settled) < 0.005f)
        assertTrue(particle.x(gathered) != particle.x(settled))
        assertTrue(distance(dispersing) > distance(settled))
        assertTrue(distance(gone) > distance(dispersing))
        assertTrue(dispersing.particleAlpha < settled.particleAlpha)
        assertEquals(0f, gone.particleAlpha, 0f)
        assertTrue(cloud.otherOutlineAlpha > 0f)
        assertEquals(0f, gathered.otherOutlineAlpha, 0f)
        assertTrue(gathered.correctOutlineAlpha > cloud.correctOutlineAlpha)
    }

    @Test
    fun electronMakesOneTurnPerSecondFromTheTop() {
        val halfPi = (PI / 2).toFloat()
        val quarterTurns = (0..4).map { QuantumCountdownFrame(it * 250f).electronAngle }
        quarterTurns.forEachIndexed { index, angle ->
            assertEquals((index - 1) * halfPi, angle, 0.0001f)
        }
    }
}
