package io.github.yamichi77.aurawalkinfo.diagnostics

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.xr.arcore.Geospatial
import androidx.xr.runtime.Config
import androidx.xr.runtime.GeospatialMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import io.github.yamichi77.aurawalkinfo.heading.HeadPoseSource
import io.github.yamichi77.aurawalkinfo.heading.HeadTracking
import io.github.yamichi77.aurawalkinfo.heading.normalizeDegrees
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun runHeadingProbe(
    context: Context,
    session: Session?,
    source: HeadPoseSource,
    geoConsent: () -> Boolean,
) {
    val sensors = context.getSystemService(SensorManager::class.java)?.getSensorList(Sensor.TYPE_ALL).orEmpty()
    sensors.forEach { logProbe("heading sensor type=${it.type} name=${it.name}") }
    val magnetic = sensors.count { it.type == Sensor.TYPE_MAGNETIC_FIELD }
    val rotation = sensors.count { it.type == Sensor.TYPE_ROTATION_VECTOR || it.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR }
    logProbe("heading magnetometer ${if (magnetic > 0) "PASS" else "FAIL"} count=$magnetic")
    logProbe("heading absoluteRotationVector ${if (rotation > 0) "PASS" else "FAIL"} count=$rotation")
    logProbe("heading geospatialApi PASS available=1")

    val states = HeadTracking.entries.associateWith { 0 }.toMutableMap()
    var yawCount = 0
    var previousYaw = 0.0
    var unwrapped = 0.0
    var minimum = 0.0
    var maximum = 0.0
    if (session != null && context.hasPermission(HEAD_TRACKING)) {
        withTimeoutOrNull(60_000) {
            source.poses(session).collect { pose ->
                states[pose.tracking] = states.getValue(pose.tracking) + 1
                pose.yawDegrees?.let { yaw ->
                    if (yawCount > 0) unwrapped += normalizeDegrees(yaw - previousYaw + 180.0) - 180.0
                    minimum = minOf(minimum, unwrapped)
                    maximum = maxOf(maximum, unwrapped)
                    previousYaw = yaw
                    yawCount++
                    // A once-per-second trace separates head turns from tracking jumps (T20 on AURA).
                    if (yawCount % 10 == 1) {
                        logProbe(String.format(Locale.ROOT, "heading yawTrace n=%d yaw=%.2f unwrapped=%.2f", yawCount, yaw, unwrapped))
                    }
                }
            }
            awaitCancellation()
        }
    }
    val lost = states.getValue(HeadTracking.STOPPED) + states.getValue(HeadTracking.PAUSED)
    val yawStatus = when {
        session == null || !context.hasPermission(HEAD_TRACKING) -> "SKIP"
        yawCount < 2 || lost > 0 -> "FAIL"
        else -> "PASS"
    }
    val counts = states.entries.joinToString(" ") { "${it.key}=${it.value}" }
    val spread = if (yawCount > 1) String.format(Locale.ROOT, "%.3f", maximum - minimum) else "NA"
    logProbe("heading yaw $yawStatus $counts yawSamples=$yawCount rangeDeg=$spread windowSec=60")
    when {
        session == null -> logProbe("heading geospatial SKIP session=0")
        !geoConsent() -> logProbe("heading geospatial SKIP consent=0")
        !context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ->
            logProbe("heading geospatial SKIP fineLocation=0")
        else -> checkGeospatial(session)
    }
    logProbe("heading result $yawStatus yawSamples=$yawCount rangeDeg=$spread")
}

private suspend fun checkGeospatial(session: Session) {
    val original = session.config
    try {
        val result = session.configure(Config.Builder(original).setGeospatial(GeospatialMode.SPATIAL).build())
        if (result !is SessionConfigureSuccess) {
            logProbe("heading geospatial FAIL configured=0 result=${result.javaClass.simpleName}")
            return
        }
        val geospatial = Geospatial.getInstance(session)
        val running = withTimeoutOrNull(5_000) {
            geospatial.state.first {
                it.geospatialTrackingState == Geospatial.GeospatialTrackingState.RUNNING
            }
        } != null
        logProbe("heading geospatial ${if (running) "PASS" else "FAIL"} configured=1 running=${if (running) 1 else 0}")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logProbe("heading geospatial FAIL error=${error.javaClass.simpleName}")
    } finally {
        if (session.config.geospatial != original.geospatial) {
            try {
                val restored = session.configure(original) is SessionConfigureSuccess
                logProbe("heading geospatialRestore ${if (restored) "PASS" else "FAIL"} restored=${if (restored) 1 else 0}")
            } catch (error: Exception) {
                logProbe("heading geospatialRestore FAIL error=${error.javaClass.simpleName}")
            }
        }
    }
}
