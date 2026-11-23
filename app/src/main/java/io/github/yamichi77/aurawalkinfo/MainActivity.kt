package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.xr.compose.platform.LocalSpatialCapabilities
import io.github.yamichi77.aurawalkinfo.diagnostics.DiagnosticsScreen
import io.github.yamichi77.aurawalkinfo.diagnostics.HEAD_TRACKING
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.hasPermission
import io.github.yamichi77.aurawalkinfo.safety.HudVisibility
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.DefaultSessionStartMode
import io.github.yamichi77.aurawalkinfo.session.SessionStartMode
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.hud.BigMapControl
import io.github.yamichi77.aurawalkinfo.ui.hud.HudQuantumStartup
import io.github.yamichi77.aurawalkinfo.ui.hud.HudScene
import io.github.yamichi77.aurawalkinfo.ui.hud.HudTarget
import io.github.yamichi77.aurawalkinfo.ui.hud.hudVisualSpecFor
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val DEBUG_TAP_ACTION = "io.github.yamichi77.aurawalkinfo.DEBUG_TAP"

internal class HudControls : ViewModel() {
    var initialized = false
    val quantumStartup = HudQuantumStartup()
    var phase by mutableStateOf<WalkPhase>(WalkPhase.Standby)
    var startingFromSummary by mutableStateOf(false)
    var map by mutableStateOf(BigMapControl())
    var seconds by mutableStateOf<Int?>(null)
}

class MainActivity : ComponentActivity() {
    private val container get() = (application as AuraWalkApplication).container
    private lateinit var visibility: HudVisibility
    private val controls by lazy { ViewModelProvider(this)[HudControls::class.java] }
    private var fakeMotion: MotionState? = null
    private var timer: Job? = null
    private var debugTapReceiver: BroadcastReceiver? = null

    private var locationGranted by mutableStateOf(false)
    private var headTrackingGranted by mutableStateOf(false)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val debugBuild = isDebugBuild()
        val mode = if (debugBuild) intent.getStringExtra("mode") else null
        val probe = mode == "probe" || mode == "map" || mode == "maplite"
        val debug = if (debugBuild) HudDebugOptions.from(intent) else HudDebugOptions()
        fakeMotion = debug.fakeState?.motionState
        val notice = container.safetyNotice
        visibility = HudVisibility(notice, debug.resetNotice || (debug.fakeState == null && !notice.hasBeenShown))
        refreshPermissions()
        if (savedInstanceState == null && !probe && debug.fakeState == null) {
            val missing = buildList {
                if (!locationGranted) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (!headTrackingGranted && packageManager.hasSystemFeature("android.software.xr.api.spatial")) {
                    add(HEAD_TRACKING)
                }
            }
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        }
        val repository = container.walkSessionRepository
        if (!probe) {
            if (!controls.initialized) {
                val startMode = debug.startMode ?: DefaultSessionStartMode
                controls.phase = if (debug.fakeState != null || startMode == SessionStartMode.AUTO) {
                    WalkPhase.Measuring
                } else {
                    WalkPhase.Standby
                }
                if (debug.fakeState == null) repository.configure(startMode, legacyReplay = debug.startMode == null)
                controls.initialized = true
                logPhase()
            }
            lifecycleScope.launch {
                var lastMotion: MotionState? = null
                repository.state.collect {
                    val motion = currentMotion()
                    if (motion != lastMotion) {
                        lastMotion = motion
                        refreshControls()
                    }
                }
            }
            if (debugBuild) registerDebugTap()
        }
        setContent {
            AdditiveDisplayTheme {
                if (probe) {
                    DiagnosticsScreen(this, mode, debug.capture)
                } else {
                    val state by repository.state.collectAsStateWithLifecycle()
                    val spec = hudVisualSpecFor(
                        debug.fakeState ?: state, noticePending = visibility.noticePending,
                        phase = controls.phase, countdown = controls.seconds,
                        startingFromSummary = controls.startingFromSummary,
                    )
                    val onTap: (HudTarget) -> Unit = { target -> handleTap(target, "compose") }
                    val denied = !locationGranted && debug.fakeState == null
                    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled && !debug.preview2d
                    if (debug.fakeState != null || debug.grid || debug.capture) {
                        LaunchedEffect(spatial, headTrackingGranted) {
                            val route = when {
                                !spatial -> "2d"
                                headTrackingGranted -> "xr"
                                else -> "head-permission-missing"
                            }
                            Log.i("AuraHud", "route=$route")
                        }
                    }
                    if (spatial) {
                        if (headTrackingGranted) {
                            HudScene(
                                spec, denied, debug, ::onNoticeTimeout, controls.map.isOpen,
                                controls.quantumStartup, onTap,
                            )
                        }
                    } else {
                        PhoneScreen(
                            spec, denied, debug, ::onNoticeTimeout, controls.map.isOpen,
                            quantumStartup = controls.quantumStartup, onTap = onTap,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        headTrackingGranted = hasPermission(HEAD_TRACKING)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val diagnosticExtras = listOf("mode", "grid", "capture", "selfcheck", "fakeState", "preview2d", "resetNotice", "startMode")
        if (isDebugBuild() && diagnosticExtras.any { intent.hasExtra(it) || this.intent.hasExtra(it) }) {
            val newFakeMotion = HudDebugOptions.from(intent).fakeState?.motionState
            if (intent.getStringExtra("startMode") != this.intent.getStringExtra("startMode") ||
                newFakeMotion != fakeMotion
            ) {
                controls.initialized = false
                controls.map = BigMapControl()
            }
            setIntent(intent)
            // Start a fresh diagnostic run even when adb reuses the top activity.
            recreate()
        }
    }

    private fun currentMotion() = fakeMotion ?: container.walkSessionRepository.state.value.motionState

    private fun onNoticeTimeout() {
        if (visibility.noticePending && currentMotion() != MotionState.FAST) {
            visibility.dismissNotice()
            if (isDebugBuild()) Log.i("AuraHud", "notice dismissed by timeout")
        }
    }

    override fun onDestroy() {
        debugTapReceiver?.let(::unregisterReceiver)
        super.onDestroy()
    }

    private fun registerDebugTap() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val target = HudTarget.entries.firstOrNull { it.name.lowercase() == intent.getStringExtra("target") }
                if (target == null) Log.w("AuraInput", "Invalid DEBUG_TAP target")
                else handleTap(target, "debug")
            }
        }
        registerReceiver(receiver, IntentFilter(DEBUG_TAP_ACTION), Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        debugTapReceiver = receiver
    }

    private fun handleTap(target: HudTarget, source: String) {
        val now = SystemClock.elapsedRealtime()
        val motion = currentMotion()
        advanceControls(now, motion)
        val hit = if (target == HudTarget.LEFT && controls.map.isOpen) HudTarget.OUTSIDE else target
        val countingDown = controls.phase.deadline != null
        val suffix = when {
            motion == MotionState.FAST -> " ignored-fast"
            hit == HudTarget.MAP && countingDown -> " ignored-countdown"
            else -> ""
        }
        Log.i("AuraInput", "CLICK target=${hit.name.lowercase()} source=$source$suffix")
        if (suffix.isEmpty()) {
            if (visibility.noticePending) {
                visibility.dismissNotice()
                if (isDebugBuild()) Log.i("AuraHud", "notice dismissed by tap")
            } else if (hit == HudTarget.LEFT || hit == HudTarget.COUNTDOWN && countingDown) {
                setPhase(controls.phase.tap(now, motion))
            } else {
                setMap(controls.map.tap(hit, now, motion), now, "tap")
            }
        }
        scheduleControls(now)
    }

    private fun refreshControls() {
        val now = SystemClock.elapsedRealtime()
        advanceControls(now, currentMotion())
        scheduleControls(now)
    }

    private fun advanceControls(now: Long, motion: MotionState) {
        setPhase(controls.phase.advance(now))
        val reason = if (motion == MotionState.FAST) "fast" else "timeout"
        setMap(controls.map.advance(now, motion), now, reason)
    }

    private fun setPhase(phase: WalkPhase) {
        val previous = controls.phase
        if (phase == previous) return
        controls.startingFromSummary = phase is WalkPhase.Starting && previous == WalkPhase.Summary
        controls.phase = phase
        if (fakeMotion == null) {
            if (phase == WalkPhase.Measuring && previous is WalkPhase.Starting) {
                container.walkSessionRepository.setMeasuring(true)
            } else if (phase == WalkPhase.Summary) {
                container.walkSessionRepository.setMeasuring(false)
            }
        }
        logPhase()
    }

    private fun logPhase() {
        if (isDebugBuild()) {
            val phase = when (controls.phase) {
                WalkPhase.Standby -> "standby"
                is WalkPhase.Starting -> "starting"
                WalkPhase.Measuring -> "measuring"
                is WalkPhase.Ending -> "ending"
                WalkPhase.Summary -> "summary"
            }
            Log.i("AuraSession", "WALK phase=$phase")
        }
    }

    private fun setMap(map: BigMapControl, now: Long, reason: String) {
        val previous = controls.map
        controls.map = map
        if (!isDebugBuild() || map == previous) return
        when {
            !map.isOpen -> Log.i("AuraBigMap", "CLOSED reason=$reason now=$now")
            !previous.isOpen -> Log.i("AuraBigMap", "OPEN now=$now deadline=${map.closesAt}")
            else -> Log.i("AuraBigMap", "SHORTEN now=$now deadline=${map.closesAt}")
        }
    }

    private fun scheduleControls(now: Long) {
        timer?.cancel()
        controls.seconds = controls.phase.remainingSeconds(now)
        val nextCountdown = controls.phase.deadline?.let { (it - now - 1) % 1_000 + 1 }
        val nextMap = controls.map.closesAt?.minus(now)
        val next = listOfNotNull(nextCountdown, nextMap).minOrNull() ?: return
        timer = lifecycleScope.launch {
            delay(next)
            refreshControls()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isDebugBuild()) {
            Log.i(
                "AuraInput",
                "key code=${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "action=${event.action} " +
                    "source=0x${event.source.toString(16)} device=${event.device?.name}",
            )
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isDebugBuild()) logMotion("generic", event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isDebugBuild()) logMotion("touch", event)
        return super.dispatchTouchEvent(event)
    }

    private fun logMotion(kind: String, event: MotionEvent) {
        Log.i(
            "AuraInput",
            "$kind action=${MotionEvent.actionToString(event.action)} " +
                "source=0x${event.source.toString(16)} device=${event.device?.name}",
        )
    }
}
