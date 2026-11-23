package io.github.yamichi77.aurawalkinfo.pics

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.yamichi77.aurawalkinfo.diagnostics.logProbe
import java.util.TreeMap
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun runPicsBleProbe(context: Context) {
    val access = context.picsScanAccess()
    val scanner = access.scanner
    if (scanner == null) {
        logProbe("BLE SKIP reason=${access.reason}")
        return
    }

    val devices = mutableSetOf<BluetoothDevice>()
    val rssi = TreeMap<Int, Int>()
    val production = CompanyCounts()
    val synthetic = CompanyCounts()
    var results = 0
    val failure = withTimeoutOrNull(20_000L) {
        scanBle(scanner, emptyList(), ScanSettings.SCAN_MODE_LOW_LATENCY) { result ->
            results++
            devices.add(result.device)
            rssi.merge(result.rssi, 1, Int::plus)
            result.scanRecord?.let { record ->
                record.getManufacturerSpecificData(PICS_COMPANY_ID)?.let(production::add)
                record.getManufacturerSpecificData(TEST_COMPANY_ID)?.let(synthetic::add)
            }
        }
    }
    fun rssiAt(index: Int): Int? {
        var count = 0
        return rssi.entries.firstOrNull { (_, frequency) ->
            count += frequency
            index < count
        }?.key
    }
    val median = if (results == 0) null else {
        (rssiAt((results - 1) / 2)!! + rssiAt(results / 2)!!) / 2.0
    }
    val outcome = if (failure == null) "PASS" else "FAIL reason=$failure"
    logProbe(
        "BLE $outcome results=$results devices=${devices.size} " +
            "rssiMin=${rssi.firstEntry()?.key} rssiMedian=$median rssiMax=${rssi.lastEntry()?.key} " +
            "company0x01CE=[$production] company0xFFFF=[$synthetic]",
    )
}

private class CompanyCounts {
    private val types = IntArray(4)

    fun add(data: ByteArray) {
        val type = data.getOrNull(2)?.toInt()
        val index = when (type) {
            0, 1, 2 -> type
            else -> 3
        }
        types[index]++
    }

    override fun toString() =
        "total=${types.sum()},type0=${types[0]},type1=${types[1]},type2=${types[2]},other=${types[3]}"
}
