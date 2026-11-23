package io.github.yamichi77.aurawalkinfo.pics

import android.Manifest
import android.bluetooth.le.ScanFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import io.github.yamichi77.aurawalkinfo.session.sessionTicks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AuraPics"
private const val REPLAY_ACTION = "io.github.yamichi77.aurawalkinfo.PICS_REPLAY"

internal class PicsReceiver(
    private val context: Context,
    private val clock: Clock,
    private val debuggable: Boolean,
) {
    private val parser = PicsParser(debuggable)
    private val liveTracker = PicsTracker(clock)
    private val foreground = Mutex()
    private val selected = MutableStateFlow<PicsCandidate?>(null)
    val candidate: StateFlow<PicsCandidate?> = selected.asStateFlow()

    // The activity runs this on the main thread and cancels it when it leaves the foreground.
    suspend fun runForeground(states: StateFlow<WalkSessionState>, fakeState: WalkSessionState?) = foreground.withLock {
        val scenarios = if (debuggable) replayCommands().onStart { emit(null) } else flowOf(null)
        scenarios.collectLatest { scenario ->
            val input = if (scenario != null && fakeState != null) MutableStateFlow(fakeState) else states
            try {
                runSession(input, scenario)
            } finally {
                selected.value = null
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun replayCommands(): Flow<PicsReplay?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val command = intent.getStringExtra("scenario")
                val replay = PicsReplay.entries.firstOrNull { it.command == command }
                when {
                    command == "stop" -> trySend(null)
                    replay != null -> trySend(replay)
                    else -> Log.w(TAG, "Invalid replay scenario")
                }
            }
        }
        context.registerReceiver(
            receiver, IntentFilter(REPLAY_ACTION), Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "PICS_REPLAY ready")
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private suspend fun runSession(states: StateFlow<WalkSessionState>, scenario: PicsReplay?) = coroutineScope {
        val tracker = if (scenario == null) liveTracker else PicsTracker(clock)
        val expiry = MutableStateFlow<Long?>(null)
        var lastReplayState: String? = null
        var receivedReplaySignal = false
        var sourceAvailable = scenario != null
        fun publish() {
            expiry.value = tracker.nextUpdateAt()
            val walk = states.value
            val result = if (sourceAvailable) tracker.nearest(walk.motionState, walk.mapCenter) else null
            selected.value = result
            if (scenario != null && receivedReplaySignal) {
                val light = result?.lights?.firstOrNull()
                val label = if (result == null) "phase=HIDDEN"
                    else "phase=${result.freshness} aspect=${light?.aspect} remaining=${light?.remaining}"
                if (label != lastReplayState) {
                    Log.i(TAG, "PICS scenario=${scenario.command} $label")
                    lastReplayState = label
                }
            }
        }
        fun receive(company: Int, data: ByteArray, rssi: Int, at: Long) {
            val packet = parser.parse(company, data) ?: return
            tracker.receive(packet, rssi, at)
            if (scenario != null && packet is PicsPacket.Signal) receivedReplaySignal = true
            publish()
        }

        launch {
            states.collect { publish() }
        }
        launch {
            expiry.collectLatest { deadline ->
                if (deadline != null) {
                    delay((deadline - clock.elapsedRealtimeMillis()).coerceAtLeast(0L))
                    publish()
                }
            }
        }
        if (scenario == null) {
            scan(states, ::receive) { available ->
                sourceAvailable = available
                publish()
            }
        } else {
            scenario.play(states) { data ->
                receive(TEST_COMPANY_ID, data, -60, clock.elapsedRealtimeMillis())
            }
        }
    }

    private suspend fun scan(
        states: Flow<WalkSessionState>,
        receive: (Int, ByteArray, Int, Long) -> Unit,
        onAvailability: (Boolean) -> Unit,
    ) {
        val filters = parser.companyIds.map { id ->
            ScanFilter.Builder().setManufacturerData(id, byteArrayOf()).build()
        }
        val modes = states.map { it.motionState }.stablePicsScanModes()
        val access = sessionTicks().onStart { emit(Unit) }
            .map { context.picsScanAccess() }.distinctUntilChanged()
        combine(access, modes, ::Pair).collectLatest { (status, mode) ->
            val scanner = status.scanner
            onAvailability(scanner != null)
            if (scanner == null) {
                Log.i(TAG, "BLE scan skipped: ${status.reason}")
            } else {
                val failure = scanBle(scanner, filters, mode) { result ->
                    val record = result.scanRecord ?: return@scanBle
                    val receivedAt = result.timestampNanos / 1_000_000L
                    parser.companyIds.forEach { company ->
                        record.getManufacturerSpecificData(company)?.let { bytes ->
                            receive(company, bytes, result.rssi, receivedAt)
                        }
                    }
                }
                onAvailability(false)
                Log.i(TAG, "BLE scan stopped: $failure")
            }
        }
    }
}
