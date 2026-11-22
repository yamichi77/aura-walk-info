package io.github.yamichi77.aurawalkinfo.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal const val PROBE_TAG = "AuraProbe"
internal const val HEAD_TRACKING = "android.permission.HEAD_TRACKING"
private const val FIX_TIMEOUT_MS = 30_000L
private const val POSE_TIMEOUT_MS = 10_000L

internal fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

internal fun elapsedSeconds(startMs: Long): String =
    String.format(Locale.ROOT, "%.2f", (SystemClock.elapsedRealtime() - startMs) / 1_000.0)

internal suspend fun runCapabilityProbe(activity: ComponentActivity, report: (String) -> Unit) =
    coroutineScope {
        val availability = GoogleApiAvailability.getInstance()
        val availabilityCode = availability.isGooglePlayServicesAvailable(activity)
        val gmsPackage = try {
            activity.packageManager.getPackageInfo(
                "com.google.android.gms",
                PackageManager.PackageInfoFlags.of(0L),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val gmsVersion = gmsPackage?.let { "${it.versionName} (${it.longVersionCode})" } ?: "not visible"
        report("Play services: ${availability.getErrorString(availabilityCode)} ($availabilityCode), $gmsVersion")

        val locations = activity.getSystemService(LocationManager::class.java)
        report("Providers: ${locations.allProviders.joinToString { "$it=${locations.isProviderEnabled(it)}" }}")
        report("GNSS: model=${locations.gnssHardwareModelName}, year=${locations.gnssYearOfHardware}")
        report("Geocoder.isPresent: ${Geocoder.isPresent()}")
        report("Permissions: fine=${activity.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)}, " +
            "head=${activity.hasPermission(HEAD_TRACKING)}")

        if (activity.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (availabilityCode == ConnectionResult.SUCCESS) {
                launch { reportFix("Fused", fusedUpdates(activity), report) }
            } else {
                report("Fused: skipped (Play services unavailable)")
            }
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                launch { reportFix("GPS", gpsUpdates(locations, activity), report) }
            } else {
                report("GPS: skipped (provider disabled or absent)")
            }
        } else {
            report("Fused / GPS: skipped (precise location permission missing)")
        }
        launch { reportArCore(activity, report) }
    }

private suspend fun reportFix(label: String, updates: Flow<Location>, report: (String) -> Unit) {
    val startMs = SystemClock.elapsedRealtime()
    val startNanos = SystemClock.elapsedRealtimeNanos()
    try {
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            updates.first { it.elapsedRealtimeNanos >= startNanos }
        }
        if (fix == null) {
            report("$label: no fresh fix within ${FIX_TIMEOUT_MS / 1_000}s")
            return
        }
        val accuracy = if (fix.hasAccuracy()) "${fix.accuracy}m" else "unavailable"
        report("$label: first fix ${elapsedSeconds(startMs)}s, accuracy=$accuracy, " +
            "provider=${fix.provider}, hasSpeed=${fix.hasSpeed()}, hasBearing=${fix.hasBearing()}")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        report("$label: failed (${error.javaClass.simpleName})")
    }
}

@SuppressLint("MissingPermission")
private fun gpsUpdates(locations: LocationManager, context: Context): Flow<Location> = callbackFlow {
    val listener = android.location.LocationListener { location -> trySend(location) }
    locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, context.mainExecutor, listener)
    awaitClose { locations.removeUpdates(listener) }
}

@SuppressLint("MissingPermission")
private fun fusedUpdates(context: Context): Flow<Location> = callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMaxUpdateAgeMillis(0)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { trySend(it) }
        }
    }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        .addOnFailureListener { close(it) }
    awaitClose { client.removeLocationUpdates(callback) }
}

private suspend fun reportArCore(activity: ComponentActivity, report: (String) -> Unit) {
    var operation = "Session.create"
    try {
        val created = Session.create(activity)
        report("XR Session.create: ${created.javaClass.simpleName}")
        if (created !is SessionCreateSuccess) return
        if (!activity.hasPermission(HEAD_TRACKING)) {
            report("XR SPATIAL: skipped (HEAD_TRACKING permission missing)")
            return
        }

        operation = "configure(SPATIAL)"
        val session = created.session
        val configured = session.configure(
            Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build(),
        )
        report("XR SPATIAL: ${configured.javaClass.simpleName}")
        if (configured !is SessionConfigureSuccess) return

        operation = "ArDevice.state"
        val states = mutableSetOf<TrackingState>()
        var firstTrackedPose: Pose? = null
        val changed = withTimeoutOrNull(POSE_TIMEOUT_MS) {
            ArDevice.getInstance(session).state.first { state ->
                states.add(state.trackingState)
                if (state.trackingState != TrackingState.TRACKING) {
                    false
                } else {
                    val previous = firstTrackedPose
                    firstTrackedPose = state.devicePose
                    previous != null && previous != state.devicePose
                }
            }
        } != null
        report("XR ArDevice: tracking=${states.joinToString()}, poseChanged=$changed " +
            "(up to ${POSE_TIMEOUT_MS / 1_000}s)")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        report("XR $operation: ${error.javaClass.simpleName}: ${error.message}")
    }
}

internal fun logProbe(message: String) {
    Log.i(PROBE_TAG, message)
}
