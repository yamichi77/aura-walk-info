package io.github.yamichi77.aurawalkinfo.session

import io.github.yamichi77.aurawalkinfo.heading.HeadPose
import io.github.yamichi77.aurawalkinfo.heading.HeadingFix
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate

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
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
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
    val heading: HeadingTracking? = null,
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
    val headPose: HeadPose? = null,
    val headingFix: HeadingFix? = null,
    val north: NorthEstimate? = null,
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
    private data class Measurement(val active: Boolean, val legacyReplay: Boolean)
    private val measurement = MutableStateFlow(Measurement(active = true, legacyReplay = true))

    private sealed interface Event {
        data class Input(val sample: LocationSample?) : Event
        data class Control(val measurement: Measurement) : Event
    }

    fun start(scope: CoroutineScope): Job = start(scope, SessionStartMode.AUTO)

    /** Starts location observation, which outlives individual walks. */
    @Synchronized
    internal fun start(scope: CoroutineScope, mode: SessionStartMode): Job {
        sessionJob?.takeUnless { it.isCompleted }?.let { return it }

        mutableState.value = WalkSessionState()
        configure(mode, legacyReplay = true)
        return scope.launch {
            inputs.collectLatest { runSession(it) }
        }.also { sessionJob = it }
    }

    internal fun configure(mode: SessionStartMode, legacyReplay: Boolean) {
        measurement.value = Measurement(mode == SessionStartMode.AUTO, legacyReplay)
    }

    internal fun setMeasuring(active: Boolean) {
        measurement.value = Measurement(active, legacyReplay = false)
    }

    private suspend fun runSession(input: WalkSessionInput) = coroutineScope {
        val clock = input.clock
        val classifier = MotionClassifier(distanceCalculator)
        var distance = DistanceTracker(distanceCalculator)
        val addressPoints = Channel<LocationSample>(Channel.CONFLATED)
        fun openMetrics() = input.record?.let { record ->
            Channel<SessionMetrics>(Channel.BUFFERED).also { channel ->
                launch { record(channel.receiveAsFlow()) }
            }
        }

        fun Measurement.isActive() = active || (input.replay && legacyReplay)
        val firstTime = clock.elapsedRealtimeMillis()
        var startedAt = firstTime.takeIf { measurement.value.isActive() }
        var metrics = if (startedAt != null) openMetrics() else null
        mutableState.update { previous ->
            if (startedAt != null) WalkSessionState() else WalkSessionState(
                distanceMeters = previous.distanceMeters,
                elapsedMillis = previous.elapsedMillis,
                walkingTimeMillis = previous.walkingTimeMillis,
            )
        }
        launch {
            input.addresses(addressPoints.receiveAsFlow()).collect { address ->
                mutableState.update { it.copy(address = address) }
            }
        }

        var lastUpdateAt = firstTime
        var walkingTime = 0L
        var motion = MotionState.NO_FIX
        var latest: LocationSample? = null
        var bearing: Float? = null
        var lastReportedSecond = if (input.replay) -1L else 0L
        var reportedDistance = 0.0
        var intervalAccuracy: Float? = null
        var intervalDecision: FixDecision? = null
        var intervalHeadingFix: HeadingFix? = null
        channelFlow {
            val control = launch { measurement.collect { send(Event.Control(it)) } }
            try {
                input.locations.sessionEvents(input.ticks).collect { send(Event.Input(it)) }
            } finally {
                control.cancel()
            }
        }.buffer(Channel.RENDEZVOUS).collect { event ->
            val now = clock.elapsedRealtimeMillis()
            val previous = latest
            val fixDeadline = previous?.let { it.elapsedRealtimeMillis + MotionThresholds.NO_FIX_MILLIS }
            val start = startedAt
            if (start != null && motion == MotionState.WALKING && fixDeadline != null) {
                walkingTime += (minOf(now, fixDeadline) - lastUpdateAt).coerceAtLeast(0L)
            }
            lastUpdateAt = now

            if (event is Event.Control) {
                val active = event.measurement.isActive()
                if (active && start == null) {
                    startedAt = now
                    distance = DistanceTracker(distanceCalculator)
                    walkingTime = 0
                    reportedDistance = 0.0
                    lastReportedSecond = if (input.replay) -1L else 0L
                    intervalAccuracy = null
                    intervalDecision = null
                    intervalHeadingFix = null
                    metrics = openMetrics()
                    mutableState.update { it.copy(distanceMeters = 0.0, walkingTimeMillis = 0, elapsedMillis = 0) }
                } else if (!active && start != null) {
                    startedAt = null
                    metrics?.close()
                    metrics = null
                    mutableState.update { it.copy(walkingTimeMillis = walkingTime, elapsedMillis = now - start) }
                }
                return@collect
            }
            val incoming = (event as Event.Input).sample
            val sample = incoming?.takeIf {
                previous == null || it.elapsedRealtimeMillis > previous.elapsedRealtimeMillis
            }
            if (sample != null) {
                // A new fix can land before a tick observes the expired one.
                if (start != null && fixDeadline != null &&
                    (now >= fixDeadline || sample.elapsedRealtimeMillis >= fixDeadline)
                ) {
                    distance.update(MotionState.NO_FIX)
                }
                latest = sample
                motion = classifier.onLocation(sample, now)
                if (input.heading != null) {
                    val headingFix = HeadingFix(
                        sample.elapsedRealtimeMillis, sample.bearingDeg?.toDouble(),
                        sample.bearingAccuracyDeg?.toDouble(), sample.speedMps?.toDouble(),
                    )
                    input.heading.onFix(headingFix, walking = motion == MotionState.WALKING)
                    if (start != null && headingFix.timeMillis >= start) intervalHeadingFix = headingFix
                }
                if (classifier.latestSpeedMps?.let { it >= MotionThresholds.WALKING_MPS } == true) {
                    bearing = sample.bearingDeg ?: bearing
                }
                if (MotionThresholds.hasFix(sample, now)) addressPoints.trySend(sample)
            } else {
                motion = classifier.onTick(now)
            }
            if (start != null && sample != null) {
                intervalAccuracy = sample.accuracyMeters
                intervalDecision = distance.update(motion, sample.takeIf { it.elapsedRealtimeMillis >= start })
            } else if (start != null) {
                distance.update(motion)
            }

            val fix = latest
            val elapsed = start?.let { now - it }
            mutableState.update {
                it.copy(
                    distanceMeters = if (start != null) distance.distanceMeters else it.distanceMeters,
                    walkingTimeMillis = if (start != null) walkingTime else it.walkingTimeMillis,
                    elapsedMillis = elapsed ?: it.elapsedMillis,
                    motionState = motion,
                    mapCenter = fix?.let { MapCenter(it.latitude, it.longitude) },
                    mapBearingDeg = bearing,
                    lastFixAccuracyMeters = fix?.accuracyMeters,
                    // Replay timestamps use the simulated clock, outside the ARCore pose clock.
                    lastFixTimeMillis = fix?.elapsedRealtimeMillis?.takeUnless { input.replay },
                    gpsCourseDeg = fix?.takeIf {
                        MotionThresholds.hasFix(it, now) &&
                            it.speedMps?.let { speed -> speed.isFinite() && speed >= MotionThresholds.WALKING_MPS } == true
                    }?.bearingDeg?.takeIf { it.isFinite() },
                )
            }
            val elapsedSeconds = elapsed?.div(1_000)
            if (elapsedSeconds != null && (input.replay || incoming == null) && elapsedSeconds > lastReportedSecond) {
                metrics?.trySend(
                    SessionMetrics(
                        elapsedSeconds, motion, classifier.averageSpeedMps,
                        if (input.replay) fix?.accuracyMeters else intervalAccuracy,
                        distance.distanceMeters - reportedDistance, intervalDecision,
                        distance.distanceMeters, walkingTime, bearing,
                        headPose = input.heading?.poseAt(now),
                        headingFix = intervalHeadingFix,
                        north = if (metrics != null) input.heading?.estimate(now) else null,
                    ),
                )
                lastReportedSecond = elapsedSeconds
                reportedDistance = distance.distanceMeters
                intervalAccuracy = null
                intervalDecision = null
                intervalHeadingFix = null
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
