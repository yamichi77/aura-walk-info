package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.location.AddressRepository
import io.github.yamichi77.aurawalkinfo.location.DistanceCalculator
import io.github.yamichi77.aurawalkinfo.location.DistanceTracker
import io.github.yamichi77.aurawalkinfo.location.LocationRepository
import io.github.yamichi77.aurawalkinfo.location.LocationSample
import io.github.yamichi77.aurawalkinfo.safety.MotionClassifier
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.safety.MotionThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalkSessionRepository(
    private val locationRepository: LocationRepository,
    private val addressRepository: AddressRepository,
    private val distanceCalculator: DistanceCalculator,
    private val clock: Clock,
    private val ticks: Flow<Unit> = sessionTicks(),
) {
    private val mutableState = MutableStateFlow(WalkSessionState())
    val state: StateFlow<WalkSessionState> = mutableState.asStateFlow()
    private var sessionJob: Job? = null

    /** Starts one session. The caller owns the scope and cancels it when the session ends. */
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        sessionJob?.takeUnless { it.isCompleted }?.let { return it }

        val startedAt = clock.elapsedRealtimeMillis()
        mutableState.value = WalkSessionState()
        return scope.launch { runSession(startedAt) }.also { sessionJob = it }
    }

    private suspend fun runSession(startedAt: Long) = coroutineScope {
        val classifier = MotionClassifier(distanceCalculator)
        val distance = DistanceTracker(distanceCalculator)
        val addressPoints = Channel<LocationSample>(Channel.CONFLATED)
        launch {
            addressRepository.observe(addressPoints.receiveAsFlow()).collect { address ->
                mutableState.update { it.copy(address = address) }
            }
        }

        var lastUpdateAt = startedAt
        var walkingTime = 0L
        var motion = MotionState.NO_FIX
        var latest: LocationSample? = null
        var bearing: Float? = null
        val tickEvents: Flow<LocationSample?> = ticks.map { null }
        merge(locationRepository.locations, tickEvents).collect { incoming ->
            val now = clock.elapsedRealtimeMillis()
            val previous = latest
            val fixDeadline = previous?.let { it.elapsedRealtimeMillis + MotionThresholds.NO_FIX_MILLIS }
            if (motion == MotionState.WALKING && fixDeadline != null) {
                walkingTime += (minOf(now, fixDeadline) - lastUpdateAt).coerceAtLeast(0L)
            }
            lastUpdateAt = now

            val sample = incoming?.takeIf {
                previous == null || it.elapsedRealtimeMillis > previous.elapsedRealtimeMillis
            }
            if (sample != null) {
                // A new fix can land before a tick observes the expired one.
                if (fixDeadline != null && (now >= fixDeadline || sample.elapsedRealtimeMillis >= fixDeadline)) {
                    distance.update(MotionState.NO_FIX)
                }
                latest = sample
                motion = classifier.onLocation(sample, now)
                if (classifier.latestSpeedMps?.let { it >= MotionThresholds.WALKING_MPS } == true) {
                    bearing = sample.bearingDeg ?: bearing
                }
                if (MotionThresholds.hasFix(sample, now)) addressPoints.trySend(sample)
            } else {
                motion = classifier.onTick(now)
            }
            distance.update(motion, sample)

            val fix = latest
            mutableState.update {
                it.copy(
                    distanceMeters = distance.distanceMeters,
                    walkingTimeMillis = walkingTime,
                    elapsedMillis = now - startedAt,
                    motionState = motion,
                    mapCenter = fix?.let { MapCenter(it.latitude, it.longitude) },
                    mapBearingDeg = bearing,
                    lastFixAccuracyMeters = fix?.accuracyMeters,
                )
            }
        }
        addressPoints.close()
    }
}

internal fun sessionTicks(): Flow<Unit> = flow {
    while (true) {
        delay(1_000)
        emit(Unit)
    }
}
