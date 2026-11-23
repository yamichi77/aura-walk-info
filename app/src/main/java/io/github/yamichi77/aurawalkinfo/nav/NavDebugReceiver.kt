package io.github.yamichi77.aurawalkinfo.nav

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.NAV_REPLAY_END_SECOND
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.NAV_REPLAY_SPEED_MPS
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.NAV_REPLAY_TURN_SECOND
import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

internal const val NAV_ACTION = "io.github.yamichi77.aurawalkinfo.NAV"

internal fun registerNavDebugReceiver(context: Context, nav: NavRepository, replay: Flow<LocationSample>) {
    context.registerReceiver(
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when {
                    intent.getStringExtra("action") == "cancel" -> nav.cancel()
                    intent.getStringExtra("scenario") == "synthetic" -> {
                        val route = syntheticNavRoute()
                        nav.startSynthetic(NavDestination(route.polyline.last(), "合成の目的地"), route, replay)
                    }
                    intent.hasExtra("query") -> intent.getStringExtra("query")?.let(nav::search)
                    intent.hasExtra("lat") && intent.hasExtra("lng") -> {
                        val lat = intent.getFloatExtra("lat", Float.NaN).toDouble()
                        val lng = intent.getFloatExtra("lng", Float.NaN).toDouble()
                        if (lat in -90.0..90.0 && lng in -180.0..180.0) nav.navigateTo(NavPoint(lat, lng))
                    }
                }
            }
        },
        IntentFilter(NAV_ACTION),
        Manifest.permission.DUMP,
        null,
        Context.RECEIVER_EXPORTED,
    )
}

internal fun syntheticNavRoute(): NavRoute {
    val north = NAV_REPLAY_TURN_SECOND * NAV_REPLAY_SPEED_MPS.toDouble()
    val east = (NAV_REPLAY_END_SECOND - NAV_REPLAY_TURN_SECOND) * NAV_REPLAY_SPEED_MPS.toDouble()
    val start = syntheticFix(0).navPoint()
    val turn = syntheticFix(0, northMeters = north).navPoint()
    val destination = syntheticFix(0, northMeters = north, eastMeters = east).navPoint()
    return NavRoute(
        distanceMeters = (north + east).roundToInt(),
        duration = Duration.ofSeconds(NAV_REPLAY_END_SECOND.toLong()),
        polyline = listOf(start, turn, destination),
        steps = listOf(
            NavStep(start, turn, north.roundToInt(), listOf(start, turn), "DEPART", "北へ進む"),
            NavStep(turn, destination, east.roundToInt(), listOf(turn, destination), "TURN_RIGHT", "右に曲がる"),
        ),
    )
}
