package io.github.yamichi77.aurawalkinfo.diagnostics

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.size
import io.github.yamichi77.aurawalkinfo.heading.HeadPoseSource

@Composable
internal fun HeadingSpeechScreen(activity: ComponentActivity, mode: String, source: HeadPoseSource) {
    val speech = mode == "speech"
    val session = if (speech) null else LocalSession.current
    var geoConsent by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (speech) "音声認識を準備中" else "正面を向いて60秒間静止してください") }
    val permission = if (speech) Manifest.permission.RECORD_AUDIO else HEAD_TRACKING
    var permissionReady by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionReady = true
    }
    LaunchedEffect(mode, session) {
        if (permissionReady) return@LaunchedEffect
        if (activity.hasPermission(permission) || !speech && session == null) permissionReady = true
        else requestPermission.launch(permission)
    }
    LaunchedEffect(mode, session, permissionReady) {
        if (!permissionReady || finished) return@LaunchedEffect
        if (speech) {
            runSpeechProbe(activity) { message = it }
        } else {
            runHeadingProbe(activity, session, source) { geoConsent }
        }
        finished = true
        message = "計測終了。AuraProbe のログを確認してください"
    }

    val panel: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Text(message)
            if (speech && !finished) {
                Text("端末内認識が利用できない場合、音声をシステムの認識サービスに送ることがあります")
            }
            if (!speech && session != null && !finished) {
                if (geoConsent) {
                    Text("静止計測のあとで Geospatial を試します")
                } else {
                    Text("Geospatial を試すと、Google がカメラ画像や位置・センサーデータを処理します")
                    Button(onClick = { geoConsent = true }) { Text("Geospatial の確認に同意する") }
                }
            }
        }
    }
    if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        Subspace { SpatialPanel(SubspaceModifier.size(440.dp, 240.dp)) { panel() } }
    } else {
        panel()
    }
}
