package io.github.yamichi77.aurawalkinfo.nav

import io.github.yamichi77.aurawalkinfo.location.LocationSample
import java.time.Duration

data class NavPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
    }
}

internal fun LocationSample.navPoint() = NavPoint(latitude, longitude)

data class NavDestination(val point: NavPoint, val name: String? = null)

data class NavCandidate(
    val id: String,
    val name: String,
    val address: String,
    val point: NavPoint,
    val distanceMeters: Double,
)

enum class NavDirection { LEFT, RIGHT, STRAIGHT, U_TURN, OTHER }

data class NavStep(
    val start: NavPoint,
    val end: NavPoint,
    val distanceMeters: Int,
    val polyline: List<NavPoint>,
    val maneuver: String,
    val instructions: String,
) {
    val direction: NavDirection get() = when (maneuver) {
        "TURN_LEFT", "TURN_SLIGHT_LEFT", "TURN_SHARP_LEFT", "KEEP_LEFT", "FORK_LEFT",
        "RAMP_LEFT", "ROUNDABOUT_LEFT" -> NavDirection.LEFT
        "TURN_RIGHT", "TURN_SLIGHT_RIGHT", "TURN_SHARP_RIGHT", "KEEP_RIGHT", "FORK_RIGHT",
        "RAMP_RIGHT", "ROUNDABOUT_RIGHT" -> NavDirection.RIGHT
        "STRAIGHT" -> NavDirection.STRAIGHT
        "UTURN_LEFT", "UTURN_RIGHT" -> NavDirection.U_TURN
        else -> NavDirection.OTHER
    }
}

data class NavRoute(
    val distanceMeters: Int,
    val duration: Duration,
    val polyline: List<NavPoint>,
    val steps: List<NavStep>,
) {
    init {
        require(distanceMeters >= 0 && !duration.isNegative && polyline.size >= 2)
    }
}

data class NavTurn(val step: NavStep, val distanceMeters: Double, val bearingDegrees: Double?)

data class NavGuidance(
    val positionOnRoute: NavPoint,
    val progressMeters: Double,
    val distanceFromRouteMeters: Double,
    val nextTurn: NavTurn?,
    val remainingDistanceMeters: Double,
    val remainingDuration: Duration,
    val destinationBearingDegrees: Double?,
    val remainingPolyline: List<NavPoint> = emptyList(),
)

enum class NavError { NETWORK, PERMISSION, NO_RESULTS, DAILY_LIMIT, NO_FIX, INVALID_INPUT, OTHER }

sealed interface NavState {
    data object Idle : NavState
    data object Searching : NavState
    data class Candidates(val items: List<NavCandidate>) : NavState
    data class Routing(val destination: NavDestination) : NavState
    data class Preview(
        val destination: NavDestination,
        val route: NavRoute,
        val origin: LocationSample,
    ) : NavState
    data class Guiding(
        val destination: NavDestination,
        val route: NavRoute,
        val guidance: NavGuidance,
        val rerouteError: NavError? = null,
    ) : NavState
    data class Rerouting(val previous: Guiding) : NavState
    data class Arrived(val destination: NavDestination) : NavState
    data class Error(val reason: NavError) : NavState
}
