package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.ui.graphics.Color
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.format.FormattedDistance
import io.github.yamichi77.aurawalkinfo.ui.format.formatAddress
import io.github.yamichi77.aurawalkinfo.ui.format.formatAveragePace
import io.github.yamichi77.aurawalkinfo.ui.format.formatDistance
import io.github.yamichi77.aurawalkinfo.ui.format.formatElapsedTime
import io.github.yamichi77.aurawalkinfo.ui.format.formatWalkingTime
import io.github.yamichi77.aurawalkinfo.ui.theme.HudAddress
import io.github.yamichi77.aurawalkinfo.ui.theme.HudArrow
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDimmed
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal sealed interface HudVisualSpec {
    data object Fast : HudVisualSpec {
        const val message = "高速移動中のため非表示"
        const val opacity = 0.85f
        const val textSize = 14f
    }

    data class Clusters(
        val opacity: Float,
        val distance: FormattedDistance,
        val distanceColor: Color,
        val walkingTime: String,
        val detail: String?,
        val address: String,
        val addressLines: Int,
        val addressColor: Color,
        val mapDiameter: Float,
        val mapLineOpacity: Float,
        val mapArrowColor: Color,
    ) : HudVisualSpec
}

internal fun hudVisualSpecFor(state: WalkSessionState): HudVisualSpec {
    if (state.motionState == MotionState.FAST) return HudVisualSpec.Fast
    val stopped = state.motionState == MotionState.STOPPED
    val noFix = state.motionState == MotionState.NO_FIX
    val detail = when {
        noFix -> "GPS 測位中…"
        stopped -> listOfNotNull(
            formatElapsedTime(state.elapsedMillis),
            formatAveragePace(state.walkingTimeMillis, state.distanceMeters),
        ).joinToString(" ・ ")
        else -> null
    }
    return HudVisualSpec.Clusters(
        opacity = if (stopped) 1f else 0.7f,
        distance = formatDistance(state.distanceMeters),
        distanceColor = if (noFix) HudDimmed else HudText,
        walkingTime = formatWalkingTime(state.walkingTimeMillis),
        detail = detail,
        address = formatAddress(state.address, multiline = stopped),
        addressLines = if (stopped) 2 else 1,
        addressColor = if (noFix || (state.address as? AddressState.Available)?.stale == true) HudDimmed else HudAddress,
        mapDiameter = if (stopped) Layout.stoppedDiameter else Layout.walkingDiameter,
        mapLineOpacity = if (noFix) 0.3f else 0.4f,
        mapArrowColor = if (noFix) HudDimmed else HudArrow,
    )
}
