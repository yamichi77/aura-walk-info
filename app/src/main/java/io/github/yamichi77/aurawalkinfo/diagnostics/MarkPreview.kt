package io.github.yamichi77.aurawalkinfo.diagnostics

import io.github.yamichi77.aurawalkinfo.nav.NavDestination
import io.github.yamichi77.aurawalkinfo.nav.NavGuidance
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.NavStep
import io.github.yamichi77.aurawalkinfo.nav.NavTurn
import io.github.yamichi77.aurawalkinfo.nav.bearingTo
import java.time.Duration
import kotlin.math.cos
import kotlin.math.sin

private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
private const val TURN_LEG_METERS = 15.0

private enum class PreviewTurn(val bearing: Double, val maneuver: String, val label: String) {
    RIGHT(90.0, "TURN_RIGHT", "右折"),
    LEFT(-90.0, "TURN_LEFT", "左折"),
    STRAIGHT(0.0, "STRAIGHT", "直進"),
    UTURN(180.0, "UTURN_RIGHT", "Uターン"),
    SLIGHT_RIGHT(45.0, "TURN_SLIGHT_RIGHT", "斜め右"),
}

/** Debug only: put the corner due north and place the destination along the chosen outgoing leg. */
internal fun markPreviewNav(origin: NavPoint, turnMeters: Double, direction: String? = null): NavState.Guiding {
    val preview = PreviewTurn.entries.firstOrNull { it.name.lowercase() == direction } ?: PreviewTurn.RIGHT
    fun offset(from: NavPoint, northMeters: Double, eastMeters: Double) = NavPoint(
        from.latitude + northMeters / METERS_PER_DEGREE_LATITUDE,
        from.longitude + eastMeters / (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(from.latitude))),
    )
    val turn = offset(origin, turnMeters, 0.0)
    val bearing = Math.toRadians(preview.bearing)
    val destination = offset(turn, TURN_LEG_METERS * cos(bearing), TURN_LEG_METERS * sin(bearing))
    val approach = NavStep(origin, turn, turnMeters.toInt(), listOf(origin, turn), "DEPART", "北へ進む")
    val turnStep = NavStep(turn, destination, TURN_LEG_METERS.toInt(), listOf(turn, destination), preview.maneuver, preview.label)
    val total = turnMeters + TURN_LEG_METERS
    val duration = Duration.ofSeconds((total / 1.2).toLong())
    val polyline = listOf(origin, turn, destination)
    return NavState.Guiding(
        destination = NavDestination(destination, "テスト目的地"),
        route = NavRoute(total.toInt(), duration, polyline, listOf(approach, turnStep)),
        guidance = NavGuidance(
            positionOnRoute = origin,
            progressMeters = 0.0,
            distanceFromRouteMeters = 0.0,
            nextTurn = NavTurn(turnStep, turnMeters, 0.0),
            remainingDistanceMeters = total,
            remainingDuration = duration,
            destinationBearingDegrees = origin.bearingTo(destination),
            remainingPolyline = polyline,
        ),
    )
}
