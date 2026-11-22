package io.github.yamichi77.aurawalkinfo

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSpatialCapabilities
import io.github.yamichi77.aurawalkinfo.diagnostics.DiagnosticsScreen
import io.github.yamichi77.aurawalkinfo.diagnostics.HEAD_TRACKING
import io.github.yamichi77.aurawalkinfo.diagnostics.HudDebugOptions
import io.github.yamichi77.aurawalkinfo.diagnostics.hasPermission
import io.github.yamichi77.aurawalkinfo.ui.fallback.PhoneScreen
import io.github.yamichi77.aurawalkinfo.ui.hud.HudScene
import io.github.yamichi77.aurawalkinfo.ui.hud.hudVisualSpecFor
import io.github.yamichi77.aurawalkinfo.ui.theme.AdditiveDisplayTheme

class MainActivity : ComponentActivity() {
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
        val repository = (application as AuraWalkApplication).container.walkSessionRepository
        setContent {
            AdditiveDisplayTheme {
                if (probe) {
                    DiagnosticsScreen(this, mode, debug.capture)
                } else {
                    val state by repository.state.collectAsStateWithLifecycle()
                    val spec = hudVisualSpecFor(debug.fakeState ?: state)
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
                        if (headTrackingGranted) HudScene(spec, denied, debug)
                    } else {
                        PhoneScreen(spec, denied, debug)
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
        val diagnosticExtras = listOf("mode", "grid", "capture", "selfcheck", "fakeState", "preview2d")
        if (isDebugBuild() && diagnosticExtras.any { intent.hasExtra(it) || this.intent.hasExtra(it) }) {
            setIntent(intent)
            // Start a fresh diagnostic run even when adb reuses the top activity.
            recreate()
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
