package io.github.yamichi77.aurawalkinfo.pics

import android.bluetooth.le.ScanSettings
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val MODE_STABILITY_MILLIS = 15_000L

@OptIn(FlowPreview::class)
internal fun Flow<MotionState>.stablePicsScanModes(): Flow<Int> = flow {
    var initial = true
    val modes = map { motion ->
        if (motion == MotionState.STOPPED) ScanSettings.SCAN_MODE_LOW_LATENCY
        else ScanSettings.SCAN_MODE_BALANCED
    }.distinctUntilChanged()
    emitAll(modes.debounce {
        if (initial) {
            initial = false
            0L
        } else {
            MODE_STABILITY_MILLIS
        }
    }.distinctUntilChanged())
}
