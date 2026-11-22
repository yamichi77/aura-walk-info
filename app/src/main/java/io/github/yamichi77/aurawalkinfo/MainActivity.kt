package io.github.yamichi77.aurawalkinfo

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import io.github.yamichi77.aurawalkinfo.diagnostics.DiagnosticsScreen

class MainActivity : ComponentActivity() {
    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = if (isDebugBuild) intent.getStringExtra("mode") else null
        val capture = isDebugBuild && intent.getBooleanExtra("capture", false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (mode == "probe" || mode == "map" || mode == "maplite") {
                    DiagnosticsScreen(this, mode, capture)
                } else if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    Subspace {
                        SpatialPanel {
                            HelloLabel(Modifier.padding(16.dp))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        HelloLabel()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isDebugBuild && (intent.hasExtra("mode") || this.intent.hasExtra("mode"))) {
            setIntent(intent)
            // Start a fresh diagnostic run even when adb reuses the top activity.
            recreate()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isDebugBuild) {
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
        if (isDebugBuild) logMotion("generic", event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isDebugBuild) logMotion("touch", event)
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

@Composable
private fun HelloLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.hello),
        modifier = modifier,
        color = Color(0xFFE0F7FF),
        style = MaterialTheme.typography.titleMedium,
    )
}
