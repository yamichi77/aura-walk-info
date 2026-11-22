package io.github.yamichi77.aurawalkinfo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val HudText = Color(0xFFE0F7FF)
internal val HudDetail = Color(0xFF9FE1CB)
internal val HudAddress = Color(0xFFB5D4F4)
internal val HudDimmed = Color(0xFF888780)
internal val HudArrow = Color(0xFF7FDBFF)

@Composable
internal fun AdditiveDisplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Transparent,
            surface = Color.Transparent,
            onBackground = HudText,
            onSurface = HudText,
        ),
        content = content,
    )
}
