package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.ui.graphics.Color
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.MapCenter
import io.github.yamichi77.aurawalkinfo.session.WalkPhase
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
    data object Hidden : HudVisualSpec

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
        val showSafetyNotice: Boolean = false,
        val mapCenter: MapCenter? = null,
        val mapBearingDeg: Float? = null,
        val mapOpacity: Float = opacity,
        val phase: WalkPhase = WalkPhase.Measuring,
        val countdown: Int? = null,
        val startingFromSummary: Boolean = false,
        val navigation: NavHudVisual? = null,
        val destinationPrompt: DestinationPrompt = DestinationPrompt.Map,
    ) : HudVisualSpec {
        val leftPhase: WalkPhase get() = leftPhaseFor(phase, startingFromSummary)
    }
}

private fun leftPhaseFor(phase: WalkPhase, startingFromSummary: Boolean) = when (phase) {
    is WalkPhase.Starting -> if (startingFromSummary) WalkPhase.Summary else WalkPhase.Standby
    is WalkPhase.Ending -> WalkPhase.Measuring
    else -> phase
}

internal fun hudVisualSpecFor(
    state: WalkSessionState,
    userHidden: Boolean = false,
    noticePending: Boolean = false,
    phase: WalkPhase = WalkPhase.Measuring,
    countdown: Int? = null,
    startingFromSummary: Boolean = false,
    navState: NavState = NavState.Idle,
    destinationPrompt: DestinationPrompt = DestinationPrompt.Map,
): HudVisualSpec {
    if (state.motionState == MotionState.FAST) return HudVisualSpec.Fast
    if (userHidden) return HudVisualSpec.Hidden
    val stopped = state.motionState == MotionState.STOPPED
    val noFix = state.motionState == MotionState.NO_FIX
    val opacity = if (stopped) 1f else 0.7f
    val leftPhase = leftPhaseFor(phase, startingFromSummary)
    val detail = when {
        stopped || leftPhase == WalkPhase.Summary -> listOfNotNull(
            formatElapsedTime(state.elapsedMillis),
            formatAveragePace(state.walkingTimeMillis, state.distanceMeters),
        ).joinToString(" ・ ")
        noFix -> "GPS 測位中…"
        else -> null
    }
    return HudVisualSpec.Clusters(
        opacity = opacity,
        distance = formatDistance(state.distanceMeters),
        distanceColor = if (noFix && leftPhase != WalkPhase.Summary) HudDimmed else HudText,
        walkingTime = formatWalkingTime(state.walkingTimeMillis),
        detail = detail,
        address = formatAddress(state.address, multiline = stopped && leftPhase != WalkPhase.Standby),
        addressLines = if (stopped) 2 else 1,
        addressColor = if (noFix || (state.address as? AddressState.Available)?.stale == true) HudDimmed else HudAddress,
        mapDiameter = if (stopped) Layout.stoppedDiameter else Layout.walkingDiameter,
        mapLineOpacity = if (noFix) 0.3f else 0.4f,
        mapArrowColor = if (noFix) HudDimmed else HudArrow,
        showSafetyNotice = noticePending,
        mapCenter = state.mapCenter,
        mapBearingDeg = state.mapBearingDeg,
        mapOpacity = if (noFix) 0.3f else opacity,
        phase = phase,
        countdown = countdown,
        startingFromSummary = startingFromSummary,
        navigation = navHudVisualFor(navState, noFix),
        destinationPrompt = destinationPrompt,
    )
}
