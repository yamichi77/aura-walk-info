package io.github.yamichi77.aurawalkinfo.pics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import io.github.yamichi77.aurawalkinfo.diagnostics.hasPermission
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal data class PicsScanAccess(val scanner: BluetoothLeScanner? = null, val reason: String? = null)

internal fun Context.supportsPicsBle() = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

@SuppressLint("MissingPermission")
internal fun Context.picsScanAccess(): PicsScanAccess {
    if (!supportsPicsBle()) return PicsScanAccess(reason = "BLE unavailable")
    if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
        !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    ) {
        return PicsScanAccess(reason = "permission missing")
    }
    return try {
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) PicsScanAccess(reason = "Bluetooth off") else PicsScanAccess(scanner)
    } catch (_: SecurityException) {
        PicsScanAccess(reason = "permission missing")
    }
}

/** Kept in the foreground coroutine. Android delivers the scan callbacks on the main thread. */
@SuppressLint("MissingPermission")
internal suspend fun scanBle(
    scanner: BluetoothLeScanner,
    filters: List<ScanFilter>,
    scanMode: Int,
    onResult: (ScanResult) -> Unit,
): String {
    val failure = CompletableDeferred<Int>()
    var active = true
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (active) onResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            if (active) results.forEach(onResult)
        }

        override fun onScanFailed(errorCode: Int) {
            active = false
            failure.complete(errorCode)
        }
    }
    try {
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        scanner.startScan(filters, settings, callback)
        return "scan error ${failure.await()}"
    } catch (_: SecurityException) {
        return "permission missing"
    } catch (error: IllegalStateException) {
        // Coroutine cancellation is also an IllegalStateException. Never report it as Bluetooth off.
        if (error is CancellationException) throw error
        return "Bluetooth off"
    } finally {
        active = false
        try {
            scanner.stopScan(callback)
        } catch (_: SecurityException) {
            // The permission can be revoked while a scan is running.
        } catch (_: IllegalStateException) {
            // Turning Bluetooth off also stops the scan.
        }
    }
}
