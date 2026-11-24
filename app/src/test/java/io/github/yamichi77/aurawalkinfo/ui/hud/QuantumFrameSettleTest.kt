package io.github.yamichi77.aurawalkinfo.ui.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantumFrameSettleTest {
    private fun QuantumFrameSettle.feed(vararg millis: Long): List<Boolean> = millis.map { onFrame(it * 1_000_000) }

    @Test fun waitsForThreeSmoothFramesAfterAStall() {
        val settle = QuantumFrameSettle()
        // First frame, a 350 ms stall while the map view is built, then frames at about 60 Hz.
        assertEquals(listOf(false, false, false, false, true), settle.feed(0, 350, 366, 383, 400))
        assertEquals(400L, settle.waitedMillis)
    }

    @Test fun startsAfterThreeFramesWhenNothingStalls() {
        val settle = QuantumFrameSettle()
        assertEquals(listOf(false, false, false, true), settle.feed(0, 16, 33, 50))
    }

    @Test fun aSlowFrameRestartsTheCount() {
        val settle = QuantumFrameSettle()
        assertEquals(listOf(false, false, false, false, false, false, true), settle.feed(0, 16, 33, 80, 96, 113, 130))
    }

    @Test fun givesUpWaitingAfterOneSecond() {
        val settle = QuantumFrameSettle()
        assertFalse(settle.onFrame(0))
        var time = 0L
        var settled = false
        while (!settled) {
            time += 40
            settled = settle.onFrame(time * 1_000_000)
        }
        assertTrue(time >= QuantumFrameSettle.TimeoutMillis)
        assertTrue(time < QuantumFrameSettle.TimeoutMillis + 40)
    }
}
