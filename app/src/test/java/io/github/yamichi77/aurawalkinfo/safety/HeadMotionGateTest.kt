package io.github.yamichi77.aurawalkinfo.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HeadMotionGateTest {
    private val gate = HeadMotionGate()
    private var time = 0L
    private var angle = 0.0

    @Test
    fun stationaryThenFastThenQuietForThreeTenthsOfASecond() {
        hold(0.0, 10)
        assertNull(gate.hiddenSinceNanos)
        hold(60.0, 10)
        assertNotNull(gate.hiddenSinceNanos)
        while (step(0.0) >= HeadMotionGate.ShowDegreesPerSecond) Unit
        hold(0.0, 29)
        assertNotNull(gate.hiddenSinceNanos)
        step(0.0)
        assertNull(gate.hiddenSinceNanos)
    }

    @Test
    fun slowRotationStaysVisibleEvenWhenQuaternionChangesSign() {
        repeat(50) {
            assertEquals(10.0, step(10.0, sign = if (it % 2 == 0) -1 else 1), 0.0001)
            assertNull(gate.hiddenSinceNanos)
        }
    }

    @Test
    fun briefSpikeAndSpeedsNearThresholdsDoNotFlicker() {
        hold(29.0, 10)
        step(34.0)
        assertNull(gate.hiddenSinceNanos)
        hold(31.0, 10)
        assertNotNull(gate.hiddenSinceNanos)
        hold(29.0, 40)
        hold(14.0, 25)
        hold(16.0, 20)
        hold(14.0, 25)
        assertNotNull(gate.hiddenSinceNanos)
        hold(14.0, 20)
        assertNull(gate.hiddenSinceNanos)
    }

    @Test
    fun trackingLossClearsHideAndReacquisitionStartsFresh() {
        hold(60.0, 10)
        assertNotNull(gate.hiddenSinceNanos)
        assertNull(gate.update(time + 10_000_000L, null))
        assertNull(gate.hiddenSinceNanos)
        assertNull(gate.update(time + 20_000_000L, HeadMotionGate.Rotation(1.0, 0.0, 0.0, 0.0)))
    }

    init {
        gate.update(time, HeadMotionGate.Rotation(0.0, 0.0, 0.0, 1.0))
    }

    private fun hold(speed: Double, ticks: Int) = repeat(ticks) { step(speed) }

    private fun step(speed: Double, sign: Int = 1): Double {
        time += 10_000_000L
        angle += speed / 100
        val half = Math.toRadians(angle) / 2
        return checkNotNull(gate.update(time, HeadMotionGate.Rotation(sign * sin(half), 0.0, 0.0, sign * cos(half))))
    }
}
