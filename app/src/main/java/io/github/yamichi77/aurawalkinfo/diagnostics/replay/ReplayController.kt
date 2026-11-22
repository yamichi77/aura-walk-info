package io.github.yamichi77.aurawalkinfo.diagnostics.replay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.Clock
import io.github.yamichi77.aurawalkinfo.session.WalkSessionInput
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

private const val TAG = "AuraSession"
internal const val REPLAY_ACTION = "io.github.yamichi77.aurawalkinfo.REPLAY"

internal class ReplayController(context: Context, clock: Clock, real: WalkSessionInput) {
    private class Request(val scenario: ReplayScenario, val speed: Float)

    // Each command, including an identical replay request, creates a fresh session.
    private val selected = MutableStateFlow<Request?>(null)
    val inputs: Flow<WalkSessionInput> = selected.map { request ->
        if (request == null) real else replayInput(request.scenario, request.speed, clock) { rows ->
            var previous = MotionState.NO_FIX
            Log.i(TAG, "REPLAY ${request.scenario.command}")
            rows.collect { row ->
                if (row.motion != previous) {
                    Log.i(TAG, "TRANSITION $previous -> ${row.motion} at ${row.elapsedSeconds}")
                    previous = row.motion
                }
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT, "SAMPLE %d %s %s %s %.3f %d %s",
                        row.elapsedSeconds, row.motion, row.averageSpeedMps ?: "-",
                        row.accuracyMeters ?: "-", row.distanceMeters, row.walkingTimeMillis,
                        row.bearingDeg ?: "-",
                    ),
                )
            }
        }
    }

    init {
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val command = intent.getStringExtra("scenario")
                    if (command == "stop") {
                        selected.value = null
                        return
                    }
                    val scenario = ReplayScenario.entries.firstOrNull { it.command == command }
                    val speed = intent.getFloatExtra("speed", 1f)
                    if (scenario == null || speed <= 0f || speed > 1_000f || !speed.isFinite()) {
                        Log.w(TAG, "Invalid replay scenario or speed")
                        return
                    }
                    selected.value = Request(scenario, speed)
                }
            },
            IntentFilter(REPLAY_ACTION),
            Manifest.permission.DUMP,
            null,
            Context.RECEIVER_EXPORTED,
        )
    }
}
