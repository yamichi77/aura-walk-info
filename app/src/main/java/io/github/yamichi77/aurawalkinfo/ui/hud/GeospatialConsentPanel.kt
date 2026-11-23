package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal const val GEOSPATIAL_NOTICE = "位置合わせのため、カメラ映像・位置情報などを Google に送り、Google が処理します。"
private const val PrivacyUrl = "https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data"

@Composable
internal fun GeospatialConsentPanel(settings: SkyBandSettings, textScale: Float, enabled: Boolean) {
    if (settings.geospatialConsent != null) return
    val uriHandler = LocalUriHandler.current
    val textStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    Column(verticalArrangement = Arrangement.spacedBy((2 * textScale).dp)) {
        Text(GEOSPATIAL_NOTICE, color = HudText, fontSize = (11 * textScale).sp,
            lineHeight = (13 * textScale).sp, style = textStyle)
        Text(
            "詳しく見る",
            Modifier.clickable(enabled = enabled) {
                try {
                    uriHandler.openUri(PrivacyUrl)
                } catch (_: IllegalArgumentException) {
                    // No browser is installed.
                }
            },
            color = HudText, fontSize = (10 * textScale).sp, lineHeight = (12 * textScale).sp,
            textDecoration = TextDecoration.Underline, style = textStyle,
        )
        Row(horizontalArrangement = Arrangement.spacedBy((8 * textScale).dp)) {
            GeospatialChoice("使う", textScale, enabled) { settings.answerGeospatialConsent(true) }
            GeospatialChoice("使わない", textScale, enabled) { settings.answerGeospatialConsent(false) }
        }
    }
}

@Composable
private fun GeospatialChoice(label: String, scale: Float, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size((42 * scale).dp).clip(CircleShape)
            .border(1.dp, QuantumCyan.copy(alpha = 0.7f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = HudText, fontSize = (9 * scale).sp, maxLines = 1)
    }
}
