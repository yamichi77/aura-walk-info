package io.github.yamichi77.aurawalkinfo.nav

import io.github.yamichi77.aurawalkinfo.location.LocationSample
import io.github.yamichi77.aurawalkinfo.safety.MotionThresholds
import io.github.yamichi77.aurawalkinfo.session.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val OFF_ROUTE_MILLIS = 8_000L
private const val REROUTE_INTERVAL_MILLIS = 30_000L
private const val MAX_REROUTES = 20
private const val ARRIVAL_DISPLAY_MILLIS = 10_000L

/** Commands and state transitions run on the supplied single-threaded scope. */
class NavRepository internal constructor(
    private val scope: CoroutineScope,
    private val service: NavService,
    private val locations: Flow<LocationSample>,
    private val clock: Clock,
) {
    private val mutableState = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = mutableState.asStateFlow()
    private var operation: Job? = null

    fun search(query: String) = command {
        replaceOperation {
            if (query.isBlank()) throw NavFailure(NavError.INVALID_INPUT)
            publish(NavState.Searching)
            withLocations(locations) { fixes ->
                val origin = awaitOrigin(fixes)
                val candidates = service.search(query.trim(), origin.navPoint()).take(3)
                if (candidates.isEmpty()) throw NavFailure(NavError.NO_RESULTS)
                publish(NavState.Candidates(candidates))
            }
        }
    }

    fun selectCandidate(candidate: NavCandidate) = scope.launch {
        if (candidate in (state.value as? NavState.Candidates)?.items.orEmpty()) {
            startRoute(NavDestination(candidate.point, candidate.name))
        }
    }

    fun navigateTo(point: NavPoint, name: String? = null) = scope.launch {
        startRoute(NavDestination(point, name))
    }

    fun cancel() = command {
        operation?.cancel()
        operation = null
        mutableState.value = NavState.Idle
    }

    internal fun startSynthetic(destination: NavDestination, route: NavRoute, replay: Flow<LocationSample>) = command {
        replaceOperation {
            publish(NavState.Routing(destination))
            withLocations(replay) { fixes ->
                guide(fixes, destination, route, firstFix = null, synthetic = true)
            }
            finishArrival()
        }
    }

    private fun command(action: () -> Unit) {
        scope.launch { action() }
    }

    private fun startRoute(destination: NavDestination) {
        // Publish Routing before the command completes, even when the scope queues new jobs.
        replaceOperation(start = CoroutineStart.UNDISPATCHED) { startLive(destination) }
    }

    private fun replaceOperation(start: CoroutineStart = CoroutineStart.DEFAULT, action: suspend () -> Unit) {
        operation?.cancel()
        operation = scope.launch(start = start) {
            try {
                action()
            } catch (failure: NavFailure) {
                publish(NavState.Error(failure.reason))
            }
        }
    }

    private suspend fun startLive(destination: NavDestination) {
        publish(NavState.Routing(destination))
        withLocations(locations) { fixes ->
            val origin = awaitOrigin(fixes)
            if (origin.hasArrived(destination)) {
                publish(NavState.Arrived(destination))
                return@withLocations
            }
            val route = service.route(origin.navPoint(), destination.point)
            currentCoroutineContext().ensureActive()
            guide(fixes, destination, route, origin, synthetic = false)
        }
        finishArrival()
    }

    private suspend fun awaitOrigin(fixes: Channel<LocationSample>): LocationSample =
        withTimeoutOrNull(MotionThresholds.NO_FIX_MILLIS) {
            for (fix in fixes) {
                if (fix.isUsable(clock.elapsedRealtimeMillis())) return@withTimeoutOrNull fix
            }
            null
        } ?: throw NavFailure(NavError.NO_FIX)

    private suspend fun withLocations(
        source: Flow<LocationSample>,
        action: suspend (Channel<LocationSample>) -> Unit,
    ) = coroutineScope {
        val fixes = Channel<LocationSample>(Channel.BUFFERED)
        val collector = launch {
            try {
                source.collect { fixes.send(it) }
            } catch (_: SecurityException) {
                // A revoked permission is equivalent to losing the fix.
            } finally {
                fixes.close()
            }
        }
        try {
            action(fixes)
        } finally {
            collector.cancel()
        }
    }

    private suspend fun guide(
        fixes: Channel<LocationSample>,
        destination: NavDestination,
        initialRoute: NavRoute,
        firstFix: LocationSample?,
        synthetic: Boolean,
    ) {
        var route = initialRoute
        var calculator = GuidanceCalculator(route, destination.point)
        val reroutes = RerouteTracker()
        var rerouteError: NavError? = null
        var replacement: NavRoute? = null
        var lastFixTime: Long? = null
        var fix = fixes.latestPendingFix() ?: firstFix ?: fixes.receiveCatching().getOrNull()

        while (fix != null) {
            var nextFix: LocationSample? = null
            val time = fix.elapsedRealtimeMillis
            val now = if (synthetic) time else clock.elapsedRealtimeMillis()
            val previousTime = lastFixTime
            if (synthetic || previousTime == null || time > previousTime) {
                lastFixTime = time
                val usable = fix.isUsable(now)
                if (!usable || (previousTime != null && time - previousTime >= MotionThresholds.NO_FIX_MILLIS)) {
                    reroutes.resetEvidence()
                }
                if (usable) {
                    val point = fix.navPoint()
                    if (fix.hasArrived(destination)) {
                        publish(NavState.Arrived(destination))
                        return
                    }

                    replacement?.let {
                        route = it
                        calculator = GuidanceCalculator(route, destination.point)
                        rerouteError = null
                        replacement = null
                    }
                    val guidance = calculator.update(point)
                    val guiding = NavState.Guiding(destination, route, guidance, rerouteError)
                    publish(guiding)
                    if (!synthetic && reroutes.shouldReroute(time, now, guidance.distanceFromRouteMeters, fix.accuracyMeters)) {
                        publish(NavState.Rerouting(guiding))
                        try {
                            replacement = service.route(point, destination.point)
                            // Wait for a fresh position before publishing values for the replacement.
                            publish(guiding)
                        } catch (failure: NavFailure) {
                            rerouteError = failure.reason
                            publish(guiding.copy(rerouteError = rerouteError))
                        }
                        nextFix = fixes.latestPendingFix()
                    }
                }
            }
            fix = nextFix ?: fixes.receiveCatching().getOrNull()
        }
    }

    private suspend fun finishArrival() {
        if (state.value is NavState.Arrived) {
            delay(ARRIVAL_DISPLAY_MILLIS)
            publish(NavState.Idle)
        }
    }

    private suspend fun publish(state: NavState) {
        currentCoroutineContext().ensureActive()
        mutableState.value = state
    }
}

// Discard positions accumulated while waiting for a route. Normal guidance processes fixes in order.
private fun Channel<LocationSample>.latestPendingFix(): LocationSample? {
    var latest = tryReceive().getOrNull() ?: return null
    while (true) latest = tryReceive().getOrNull() ?: return latest
}

private fun LocationSample.isUsable(now: Long): Boolean =
    now >= elapsedRealtimeMillis && accuracyMeters >= 0f && MotionThresholds.hasFix(this, now) &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun LocationSample.hasArrived(destination: NavDestination): Boolean =
    accuracyMeters <= 25f && navPoint().distanceTo(destination.point) <= 20.0

internal class RerouteTracker {
    private var offRouteSince: Long? = null
    private var lastAttempt: Long? = null
    private var attempts = 0

    fun resetEvidence() {
        offRouteSince = null
    }

    fun shouldReroute(time: Long, now: Long, distance: Double, accuracy: Float): Boolean {
        if (distance <= maxOf(25.0, accuracy * 2.0)) {
            resetEvidence()
            return false
        }
        val started = offRouteSince ?: time.also { offRouteSince = it }
        if (time - started < OFF_ROUTE_MILLIS || attempts >= MAX_REROUTES ||
            lastAttempt?.let { now - it < REROUTE_INTERVAL_MILLIS } == true
        ) return false

        lastAttempt = now
        attempts++
        resetEvidence()
        return true
    }
}
