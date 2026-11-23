package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.ui.graphics.Color
import io.github.yamichi77.aurawalkinfo.nav.NavError
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.NavTurn
import io.github.yamichi77.aurawalkinfo.ui.format.formatDistance
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDimmed
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class NavHudVisual(
    val state: String,
    val primary: String,
    val secondary: String?,
    val primaryColor: Color,
    val secondaryColor: Color,
    val route: NavMapRoute? = null,
    val primaryForLog: String = primary,
    val arrow: NavArrowInput? = null,
)

internal data class NavMapRoute(
    val remaining: List<NavPoint>,
    val destination: NavPoint,
    val nextTurn: NavPoint?,
    val opacity: Float,
)

internal val NavState.isNavigationActive: Boolean
    get() = this is NavState.Guiding || this is NavState.Rerouting || this is NavState.Arrived

internal fun navHudVisualFor(state: NavState, noFix: Boolean, gpsCourseDeg: Float? = null): NavHudVisual? {
    val primaryColor = if (noFix) HudDimmed else HudText
    val guiding = when (state) {
        is NavState.Guiding -> state
        is NavState.Rerouting -> state.previous
        is NavState.Arrived -> return NavHudVisual("arrived", "到着しました", null, primaryColor, HudDetail)
        else -> return null
    }
    val guidance = guiding.guidance
    val turn = guidance.nextTurn
    val primary = turn?.let(::formatNavTurn) ?: "目的地へ"
    val error = guiding.rerouteError.takeUnless { state is NavState.Rerouting }
    val secondary = when {
        state is NavState.Rerouting -> "経路を取り直し中…"
        error != null -> error.hudMessage(searching = false)
        else -> "残り ${navDistance(guidance.remainingDistanceMeters)}・" +
            "${ceil(guidance.remainingDuration.toMillis() / 60_000.0).toLong()}分"
    }
    return NavHudVisual(
        state = when {
            state is NavState.Rerouting -> "rerouting"
            error != null -> "reroute-error"
            else -> "guiding"
        },
        primary = primary,
        secondary = secondary,
        primaryColor = primaryColor,
        secondaryColor = if (noFix || error != null) HudDimmed else HudDetail,
        route = NavMapRoute(
            guidance.remainingPolyline, guiding.destination.point, turn?.step?.start, if (noFix) 0.3f else 0.6f,
        ),
        // Free-form instructions can contain place names. Never log them.
        primaryForLog = if (turn == null || navManeuver(turn) != null) primary else "［案内文省略］",
        arrow = navArrowTravelBearing(guidance)?.let { NavArrowInput(it, gpsCourseDeg, noFix) },
    )
}

internal fun navDistance(meters: Double) = formatDistance(meters).let { it.number + it.unit }

internal fun roundedNavTurnDistance(meters: Double): String = when {
    meters < 100 -> "${(meters / 5).roundToInt() * 5}m"
    meters < 1_000 -> "${(meters / 10).roundToInt() * 10}m"
    else -> "%.1fkm".format(Locale.ROOT, meters / 1_000)
}

internal fun formatNavTurn(turn: NavTurn): String {
    val distance = if (turn.distanceMeters <= 10) "まもなく" else "${roundedNavTurnDistance(turn.distanceMeters)}先"
    val direction = navManeuver(turn) ?: turn.step.instructions
        .replace(Regex("\\s+"), " ").trim().let { text ->
            val limit = 12
            if (text.codePointCount(0, text.length) <= limit) text
            else text.substring(0, text.offsetByCodePoints(0, limit - 1)) + "…"
        }
    return listOf(distance, direction).filter(String::isNotEmpty).joinToString(" ")
}

private fun navManeuver(turn: NavTurn): String? = when (turn.step.maneuver) {
    "TURN_LEFT", "KEEP_LEFT", "FORK_LEFT", "RAMP_LEFT", "ROUNDABOUT_LEFT" -> "左"
    "TURN_RIGHT", "KEEP_RIGHT", "FORK_RIGHT", "RAMP_RIGHT", "ROUNDABOUT_RIGHT" -> "右"
    "TURN_SLIGHT_LEFT" -> "左斜め"
    "TURN_SLIGHT_RIGHT" -> "右斜め"
    "TURN_SHARP_LEFT" -> "左に大きく"
    "TURN_SHARP_RIGHT" -> "右に大きく"
    "UTURN_LEFT", "UTURN_RIGHT" -> "Uターン"
    "STRAIGHT" -> "直進"
    else -> null
}

internal fun NavError.hudMessage(searching: Boolean): String = when (this) {
    NavError.NETWORK -> "通信できません"
    NavError.PERMISSION -> "APIキー・権限を確認してください"
    NavError.NO_RESULTS -> if (searching) "見つかりませんでした" else "経路が見つかりませんでした"
    NavError.DAILY_LIMIT -> if (searching) "今日の検索の上限です" else "今日の経路取得の上限です"
    NavError.NO_FIX -> "現在地を取得できません"
    NavError.INVALID_INPUT -> "施設名を聞き取れませんでした"
    NavError.OTHER -> if (searching) "検索できませんでした" else "経路を取得できませんでした"
}
