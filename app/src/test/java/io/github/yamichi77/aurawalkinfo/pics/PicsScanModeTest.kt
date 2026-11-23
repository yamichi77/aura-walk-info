package io.github.yamichi77.aurawalkinfo.pics

import android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
import android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PicsScanModeTest {
    @Test
    fun changesModeOnlyAfterFifteenContinuousSecondsInEitherDirection() = runTest {
        val motion = MutableStateFlow(MotionState.STOPPED)
        val starts = mutableListOf<Pair<Long, Int>>()
        backgroundScope.launch {
            motion.stablePicsScanModes().collect { starts.add(testScheduler.currentTime to it) }
        }
        runCurrent()
        assertEquals(listOf(0L to SCAN_MODE_LOW_LATENCY), starts)

        motion.value = MotionState.WALKING
        advanceTimeBy(14_000)
        motion.value = MotionState.STOPPED
        advanceTimeBy(1_000)
        assertEquals(1, starts.size)

        motion.value = MotionState.WALKING
        advanceTimeBy(10_000)
        motion.value = MotionState.FAST // Same requested scan mode as walking.
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, starts.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(30_000L to SCAN_MODE_BALANCED, starts.last())

        motion.value = MotionState.STOPPED
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(2, starts.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(45_000L to SCAN_MODE_LOW_LATENCY, starts.last())
    }

    @Test
    fun rapidAndSustainedMotionChangesKeepScanStartsUnderTheThirtySecondLimit() = runTest {
        val motion = MutableStateFlow(MotionState.WALKING)
        val starts = mutableListOf<Long>()
        backgroundScope.launch {
            motion.stablePicsScanModes().collect { starts.add(testScheduler.currentTime) }
        }
        runCurrent()

        repeat(60) { change ->
            motion.value = if (change % 2 == 0) MotionState.STOPPED else MotionState.WALKING
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals(listOf(0L), starts)

        repeat(6) { change ->
            motion.value = if (change % 2 == 0) MotionState.STOPPED else MotionState.WALKING
            advanceTimeBy(15_000)
            runCurrent()
        }
        assertEquals(listOf(0L, 75_000L, 90_000L, 105_000L, 120_000L, 135_000L, 150_000L), starts)
        starts.forEach { from ->
            assertTrue(starts.count { it in from..(from + 30_000) } <= 5)
        }
    }
}
