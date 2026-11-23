package io.github.yamichi77.aurawalkinfo.diagnostics.replay

import io.github.yamichi77.aurawalkinfo.location.AddressParts
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.location.LocationRepository
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.SessionMetrics
import io.github.yamichi77.aurawalkinfo.session.WalkSessionInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn

internal const val NAV_REPLAY_TURN_SECOND = 30
internal const val NAV_REPLAY_END_SECOND = 70
internal const val NAV_REPLAY_SPEED_MPS = 1.3f

internal enum class ReplayScenario(val command: String, val durationSeconds: Int) {
    WALK_STOP("walk_stop", 150),
    FAST("fast", 90),
    NO_FIX("nofix", 85),
    NAVIGATION("navigation", NAV_REPLAY_END_SECOND + 1),
}

internal class ReplayLocationRepository(
    private val scenario: ReplayScenario,
    private val speed: Float,
    private val wallClock: Clock,
    scope: CoroutineScope? = null,
) : LocationRepository {
    private var routeMillis = 0L
    val clock = Clock { routeMillis }
    private val events = if (scope != null && scenario == ReplayScenario.NAVIGATION) {
        route().buffer(0).shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    } else {
        route()
    }
    override val locations: Flow<LocationSample> get() = events.filterNotNull()

    // Use one event per simulated second, so a fix and a timeout never race at 4x.
    override fun sessionEvents(ticks: Flow<Unit>): Flow<LocationSample?> = events

    private fun route(): Flow<LocationSample?> = flow {
        val startedAt = wallClock.elapsedRealtimeMillis()
        val isNavigation = scenario == ReplayScenario.NAVIGATION
        var second = 0L
        var north = 0.0
        var east = 0.0
        while (true) {
            val targetMillis = (second * 1_000.0 / speed).toLong()
            if (second > 0) delay((targetMillis - (wallClock.elapsedRealtimeMillis() - startedAt)).coerceAtLeast(1))
            routeMillis = second * 1_000
            val sample = if (second < scenario.durationSeconds) {
                val t = second.toInt()
                val stopped = scenario == ReplayScenario.WALK_STOP && t in 60..89
                val turned = if (isNavigation) t > NAV_REPLAY_TURN_SECOND else
                    scenario == ReplayScenario.WALK_STOP && t >= 120
                val missing = scenario == ReplayScenario.NO_FIX && t in 30..44
                val poor = scenario == ReplayScenario.NO_FIX && t in 45..54
                val metersPerSecond = when {
                    isNavigation -> NAV_REPLAY_SPEED_MPS
                    stopped -> (t % 3) / 10f
                    scenario == ReplayScenario.FAST && t in 30..59 -> 6f
                    else -> 1.3f
                }
                if (second > 0 && !stopped) {
                    if (turned) east += metersPerSecond else north += metersPerSecond
                }
                if (missing) null else syntheticFix(
                    millis = routeMillis,
                    northMeters = north + if (stopped) (t % 5 - 2) * 0.35 else 0.0,
                    eastMeters = east,
                    accuracy = when {
                        poor -> 40f
                        stopped -> 5f + t % 11
                        else -> 4.5f + (t % 3) * 0.5f
                    },
                    speed = metersPerSecond,
                    bearing = if (turned) 90f else 0f,
                    originLatitude = if (isNavigation) 0.0 else 35.6812,
                    originLongitude = if (isNavigation) 0.0 else 139.7658,
                )
            } else null
            emit(sample)
            second++
        }
    }
}

internal fun replayInput(
    scenario: ReplayScenario,
    speed: Float,
    wallClock: Clock,
    scope: CoroutineScope? = null,
    record: suspend (Flow<SessionMetrics>) -> Unit,
): WalkSessionInput {
    val locations = ReplayLocationRepository(scenario, speed, wallClock, scope)
    return WalkSessionInput(
        locations = locations,
        clock = locations.clock,
        addresses = {
            if (scenario == ReplayScenario.NAVIGATION) flowOf(AddressState.Failed)
            else flowOf(AddressState.Available(AddressParts("東京都", "千代田区", "丸の内", "1丁目")))
        },
        replay = true,
        record = record,
    )
}
