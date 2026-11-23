package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.safety.MotionState

data class MapCenter(val latitude: Double, val longitude: Double)

data class WalkSessionState(
    val distanceMeters: Double = 0.0,
    val walkingTimeMillis: Long = 0,
    val elapsedMillis: Long = 0,
    val motionState: MotionState = MotionState.NO_FIX,
    val address: AddressState = AddressState.Loading,
    val mapCenter: MapCenter? = null,
    val mapBearingDeg: Float? = null,
    val lastFixAccuracyMeters: Float? = null,
    val gpsCourseDeg: Float? = null,
)
