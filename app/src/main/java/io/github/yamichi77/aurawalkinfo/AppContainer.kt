package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import io.github.yamichi77.aurawalkinfo.location.AddressRepository
import io.github.yamichi77.aurawalkinfo.location.AndroidReverseGeocoder
import io.github.yamichi77.aurawalkinfo.location.FusedLocationRepository
import io.github.yamichi77.aurawalkinfo.location.GpsLocationRepository
import io.github.yamichi77.aurawalkinfo.location.LocationRepository
import io.github.yamichi77.aurawalkinfo.location.androidDistanceCalculator
import io.github.yamichi77.aurawalkinfo.safety.SafetyNotice
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.WalkSessionRepository
import io.github.yamichi77.aurawalkinfo.session.sessionTicks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val clock = Clock(SystemClock::elapsedRealtime)
    private val locationProvider: LocationRepository by lazy {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS
        ) {
            FusedLocationRepository(LocationServices.getFusedLocationProviderClient(appContext))
        } else {
            GpsLocationRepository(appContext)
        }
    }

    // A running session starts receiving locations after permission is granted by its UI.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val permissionGatedLocation = object : LocationRepository {
        override val locations = sessionTicks()
            .onStart { emit(Unit) }
            .map {
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            }
            .distinctUntilChanged()
            .flatMapLatest { granted ->
                if (granted) locationProvider.locations.catch { failure ->
                    // Permission can be revoked between the check and provider registration.
                    if (failure !is SecurityException) throw failure
                } else emptyFlow()
            }
    }

    val walkSessionRepository = WalkSessionRepository(
        permissionGatedLocation,
        AddressRepository(AndroidReverseGeocoder(appContext), androidDistanceCalculator, clock),
        androidDistanceCalculator,
        clock,
    )
    val safetyNotice = SafetyNotice(appContext)
}
