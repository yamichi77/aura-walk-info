package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role

internal fun Modifier.hudClick(
    target: HudTarget, onTap: (HudTarget) -> Unit, enabled: Boolean = true,
): Modifier = testTag(target.name.lowercase()).clickable(
    interactionSource = null, indication = null, enabled = enabled, role = Role.Button,
) { onTap(target) }
