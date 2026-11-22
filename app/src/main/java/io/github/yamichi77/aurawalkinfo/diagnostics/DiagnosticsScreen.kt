package io.github.yamichi77.aurawalkinfo.diagnostics

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.size

@Composable
internal fun DiagnosticsScreen(activity: ComponentActivity, mode: String, capture: Boolean) {
    val caps = LocalSpatialCapabilities.current
    if (mode == "probe") {
        val lines = remember { mutableStateListOf<String>() }
        var permissionsReady by remember { mutableStateOf(false) }
        var finished by remember { mutableStateOf(false) }
        val requestPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissionsReady = true }
        val spatialSummary = "Spatial: ui=${caps.isSpatialUiEnabled}, 3d=${caps.isContent3dEnabled}, " +
            "environment=${caps.isAppEnvironmentEnabled}, passthrough=${caps.isPassthroughControlEnabled}, " +
            "audio=${caps.isSpatialAudioEnabled}"

        LaunchedEffect(spatialSummary) { logProbe(spatialSummary) }
        LaunchedEffect(Unit) {
            val missing = buildList {
                if (!activity.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (!activity.hasPermission(HEAD_TRACKING)) add(HEAD_TRACKING)
            }
            if (missing.isEmpty()) permissionsReady = true
            else requestPermissions.launch(missing.toTypedArray())
        }
        LaunchedEffect(permissionsReady) {
            if (permissionsReady) {
                runCapabilityProbe(activity) { line ->
                    logProbe(line)
                    lines.add(line)
                }
                finished = true
                logProbe("probe: complete")
            }
        }

        val captureName = if (capture) {
            if (finished) "probe-panel.png" else "probe-initial-panel.png"
        } else {
            null
        }
        val panel: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize()
                    .capturePanelOnNextDraw(captureName)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Text("AURA capability probe", style = MaterialTheme.typography.titleMedium)
                    Text(spatialSummary)
                    lines.forEach { Text(it) }
                    if (!finished) Text("Probe in progress")
                }
            }
        }
        if (caps.isSpatialUiEnabled) {
            Subspace {
                SpatialPanel(SubspaceModifier.size(520.dp, 440.dp)) { panel() }
            }
        } else {
            panel()
        }
    } else {
        val spatialUiEnabled = caps.isSpatialUiEnabled
        LaunchedEffect(mode, spatialUiEnabled) {
            logProbe("$mode: spatialUi=$spatialUiEnabled")
        }
        val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            Box(panelModifier.capturePanelOnNextDraw(if (capture) "$mode-panel.png" else null)) {
                MapProbe(mode, capture, spatial = spatialUiEnabled)
            }
        }
        if (spatialUiEnabled) {
            Subspace {
                SpatialPanel(SubspaceModifier.size(400.dp)) {
                    panel(Modifier.fillMaxSize())
                }
            }
        } else {
            panel(Modifier.size(400.dp))
        }
    }
}
