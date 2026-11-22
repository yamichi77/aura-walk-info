package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSpatialCapabilities
import io.github.yamichi77.aurawalkinfo.diagnostics.DiagnosticsScreen
import io.github.yamichi77.aurawalkinfo.diagnostics.HEAD_TRACKING
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.hasPermission
import io.github.yamichi77.aurawalkinfo.safety.HudVisibility
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.hud.HudScene
import io.github.yamichi77.aurawalkinfo.ui.hud.hudVisualSpecFor
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as AuraWalkApplication).container
    private lateinit var visibility: HudVisibility
    private var hudInputEnabled = false
    private var fakeMotion: MotionState? = null
    private var motionTap: MotionTap? = null
    private data class MotionTap(val time: Long, val x: Float, val y: Float, val deviceId: Int)

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
        hudInputEnabled = !probe
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
        setContent {
            AdditiveDisplayTheme {
                if (probe) {
                    DiagnosticsScreen(this, mode, debug.capture)
                } else {
                    val state by repository.state.collectAsStateWithLifecycle()
                    val spec = hudVisualSpecFor(debug.fakeState ?: state, visibility.hidden, visibility.noticePending)
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
                        if (headTrackingGranted) HudScene(spec, denied, debug, ::onNoticeTimeout)
                    } else {
                        PhoneScreen(spec, denied, debug, ::onNoticeTimeout)
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
        val diagnosticExtras = listOf("mode", "grid", "capture", "selfcheck", "fakeState", "preview2d", "resetNotice")
        if (isDebugBuild() && diagnosticExtras.any { intent.hasExtra(it) || this.intent.hasExtra(it) }) {
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

    private fun activateHud(kind: String, source: Int, time: Long) {
        val result = visibility.activate(currentMotion(), time)
        Log.i("AuraInput", "accepted $kind source=0x${source.toString(16)} result=$result")
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
        if (hudInputEnabled && event.keyCode in hudKeys) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                activateHud(KeyEvent.keyCodeToString(event.keyCode), event.source, event.eventTime)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isDebugBuild()) logMotion("generic", event)
        if (handleHudMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isDebugBuild()) logMotion("touch", event)
        if (handleHudMotion(event)) return true
        return super.dispatchTouchEvent(event)
    }

    private fun handleHudMotion(event: MotionEvent): Boolean {
        if (!hudInputEnabled || (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD) &&
                !event.isFromSource(InputDevice.SOURCE_MOUSE))) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                motionTap = MotionTap(event.eventTime, event.x, event.y, event.deviceId)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val tap = motionTap ?: return false
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                val dx = event.x - tap.x
                val dy = event.y - tap.y
                if (tap.deviceId != event.deviceId || dx * dx + dy * dy > slop.toFloat() * slop ||
                    event.eventTime - tap.time > 300L
                ) {
                    motionTap = null
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    motionTap = null
                    activateHud("tap", event.source, event.eventTime)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> motionTap = null
        }
        return false
    }

    private fun logMotion(kind: String, event: MotionEvent) {
        Log.i(
            "AuraInput",
            "$kind action=${MotionEvent.actionToString(event.action)} " +
                "source=0x${event.source.toString(16)} device=${event.device?.name}",
        )
    }

    private companion object {
        val hudKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A)
    }
}
