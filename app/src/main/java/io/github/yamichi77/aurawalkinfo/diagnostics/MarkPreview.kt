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

private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
private const val TURN_LEG_METERS = 15.0

/**
 * Debug only (`--es mode markpreview`): a synthetic route that puts a right turn [turnMeters] ahead
 * of [origin] (due north) and the destination 15 m east of the turn. With the debug north override at
 * 0°, north is the direction the head faced when tracking started, so the arrow appears straight ahead.
 */
internal fun markPreviewNav(origin: NavPoint, turnMeters: Double): NavState.Guiding {
    fun offset(from: NavPoint, northMeters: Double, eastMeters: Double) = NavPoint(
        from.latitude + northMeters / METERS_PER_DEGREE_LATITUDE,
        from.longitude + eastMeters / (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(from.latitude))),
    )
    val turn = offset(origin, turnMeters, 0.0)
    val destination = offset(turn, 0.0, TURN_LEG_METERS)
    val approach = NavStep(origin, turn, turnMeters.toInt(), listOf(origin, turn), "DEPART", "北へ進む")
    val turnStep = NavStep(turn, destination, TURN_LEG_METERS.toInt(), listOf(turn, destination), "TURN_RIGHT", "右折")
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
