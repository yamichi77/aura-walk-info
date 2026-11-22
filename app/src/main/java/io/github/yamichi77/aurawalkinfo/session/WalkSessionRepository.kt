package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.location.AddressRepository
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.location.DistanceCalculator
import io.github.yamichi77.aurawalkinfo.location.DistanceTracker
import io.github.yamichi77.aurawalkinfo.location.FixDecision
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class WalkSessionInput(
    val locations: LocationRepository,
    val clock: Clock,
    val addresses: (Flow<LocationSample>) -> Flow<AddressState>,
    val ticks: Flow<Unit> = sessionTicks(),
    val replay: Boolean = false,
    val record: (suspend (Flow<SessionMetrics>) -> Unit)? = null,
)

internal data class SessionMetrics(
    val elapsedSeconds: Long,
    val motion: MotionState,
    val averageSpeedMps: Double?,
    val accuracyMeters: Float?,
    val addedDistanceMeters: Double,
    val fixDecision: FixDecision?,
    val distanceMeters: Double,
    val walkingTimeMillis: Long,
    val bearingDeg: Float?,
)

class WalkSessionRepository internal constructor(
    private val inputs: Flow<WalkSessionInput>,
    private val distanceCalculator: DistanceCalculator,
) {
    constructor(
        locationRepository: LocationRepository,
        addressRepository: AddressRepository,
        distanceCalculator: DistanceCalculator,
        clock: Clock,
        ticks: Flow<Unit> = sessionTicks(),
    ) : this(flowOf(WalkSessionInput(locationRepository, clock, addressRepository::observe, ticks)), distanceCalculator)

    private val mutableState = MutableStateFlow(WalkSessionState())
    val state: StateFlow<WalkSessionState> = mutableState.asStateFlow()
    private var sessionJob: Job? = null

    /** Starts one session. The caller owns the scope and cancels it when the session ends. */
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        sessionJob?.takeUnless { it.isCompleted }?.let { return it }

        mutableState.value = WalkSessionState()
        return scope.launch {
            inputs.collectLatest { runSession(it) }
        }.also { sessionJob = it }
    }

    private suspend fun runSession(input: WalkSessionInput) = coroutineScope {
        val clock = input.clock
        val startedAt = clock.elapsedRealtimeMillis()
        mutableState.value = WalkSessionState()
        val classifier = MotionClassifier(distanceCalculator)
        val distance = DistanceTracker(distanceCalculator)
        val addressPoints = Channel<LocationSample>(Channel.CONFLATED)
        val metrics = input.record?.let { record ->
            Channel<SessionMetrics>(Channel.BUFFERED).also { channel ->
                launch { record(channel.receiveAsFlow()) }
            }
        }
        launch {
            input.addresses(addressPoints.receiveAsFlow()).collect { address ->
                mutableState.update { it.copy(address = address) }
            }
        }

        var lastUpdateAt = startedAt
        var walkingTime = 0L
        var motion = MotionState.NO_FIX
        var latest: LocationSample? = null
        var bearing: Float? = null
        var lastReportedSecond = if (input.replay) -1L else 0L
        var reportedDistance = 0.0
        var intervalAccuracy: Float? = null
        var intervalDecision: FixDecision? = null
        input.locations.sessionEvents(input.ticks).collect { incoming ->
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
            val decision = distance.update(motion, sample)
            if (sample != null) {
                intervalAccuracy = sample.accuracyMeters
                intervalDecision = decision
            }

            val fix = latest
            val elapsed = now - startedAt
            mutableState.update {
                it.copy(
                    distanceMeters = distance.distanceMeters,
                    walkingTimeMillis = walkingTime,
                    elapsedMillis = elapsed,
                    motionState = motion,
                    mapCenter = fix?.let { MapCenter(it.latitude, it.longitude) },
                    mapBearingDeg = bearing,
                    lastFixAccuracyMeters = fix?.accuracyMeters,
                )
            }
            val elapsedSeconds = elapsed / 1_000
            if (metrics != null && (input.replay || incoming == null) && elapsedSeconds > lastReportedSecond) {
                metrics.trySend(
                    SessionMetrics(
                        elapsedSeconds, motion, classifier.averageSpeedMps,
                        if (input.replay) fix?.accuracyMeters else intervalAccuracy,
                        distance.distanceMeters - reportedDistance, intervalDecision,
                        distance.distanceMeters, walkingTime, bearing,
                    ),
                )
                lastReportedSecond = elapsedSeconds
                reportedDistance = distance.distanceMeters
                intervalAccuracy = null
                intervalDecision = null
            }
        }
        addressPoints.close()
        metrics?.close()
    }
}

internal fun sessionTicks(): Flow<Unit> = flow {
    while (true) {
        delay(1_000)
        emit(Unit)
    }
}
