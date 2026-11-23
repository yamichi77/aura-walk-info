package io.github.yamichi77.aurawalkinfo.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val LOCATION_INTERVAL_MILLIS = 1_000L

class FusedLocationRepository(private val client: FusedLocationProviderClient) : LocationRepository {
    @get:RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override val locations: Flow<LocationSample> get() = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MILLIS)
            .setMaxUpdateAgeMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toSample()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it.takeIf { failure -> failure is SecurityException }) }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

class GpsLocationRepository(private val context: Context) : LocationRepository {
    @get:RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override val locations: Flow<LocationSample> get() = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.hasProvider(LocationManager.GPS_PROVIDER)) return@callbackFlow
        val listener = LocationListener { trySend(it.toSample()) }
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            LOCATION_INTERVAL_MILLIS,
            0f,
            ContextCompat.getMainExecutor(context),
            listener,
        )
        awaitClose { manager.removeUpdates(listener) }
    }
}

internal val androidDistanceCalculator = DistanceCalculator { from, to ->
    val result = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, result)
    result[0].toDouble()
}

private fun Location.toSample() = LocationSample(
    elapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else Float.POSITIVE_INFINITY,
    speedMps = speed.takeIf { hasSpeed() },
    bearingDeg = bearing.takeIf { hasBearing() },
    bearingAccuracyDeg = bearingAccuracyDegrees.takeIf { hasBearingAccuracy() },
)
