package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.nav.GuidanceCalculator
import io.github.yamichi77.aurawalkinfo.nav.NavDestination
import io.github.yamichi77.aurawalkinfo.nav.NavError
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavRoute
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.NavStep
import io.github.yamichi77.aurawalkinfo.nav.NavTurn
import io.github.yamichi77.aurawalkinfo.nav.syntheticNavRoute
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDimmed
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHudVisualTest {
    private val start = NavPoint(0.0, 0.0)
    private val end = NavPoint(0.01, 0.0)

    @Test
    fun roundsTurnDistanceByBandAndSwitchesAtTenMeters() {
        assertEquals("まもなく 右", formatNavTurn(turn("TURN_RIGHT", 10.0)))
        assertEquals("10m先 右", formatNavTurn(turn("TURN_RIGHT", 10.01)))
        assertEquals("35m先 右", formatNavTurn(turn("TURN_RIGHT", 33.0)))
        assertEquals("150m先 右", formatNavTurn(turn("TURN_RIGHT", 147.0)))
        assertEquals("1.2km先 右", formatNavTurn(turn("TURN_RIGHT", 1_241.0)))
    }

    @Test
    fun formatsDirectionsAndShortensFreeFormInstructions() {
        val cases = mapOf(
            "TURN_LEFT" to "左", "TURN_RIGHT" to "右", "TURN_SLIGHT_LEFT" to "左斜め",
            "TURN_SLIGHT_RIGHT" to "右斜め", "TURN_SHARP_LEFT" to "左に大きく",
            "TURN_SHARP_RIGHT" to "右に大きく", "UTURN_LEFT" to "Uターン", "STRAIGHT" to "直進",
        )
        for ((maneuver, label) in cases) assertEquals("30m先 $label", formatNavTurn(turn(maneuver, 30.0)))
        assertEquals(
            "30m先 合成の施設の横を進み、…",
            formatNavTurn(turn("UNKNOWN", 30.0, "合成の施設の横を進み、北口を出る")),
        )
    }

    @Test
    fun remainingTimeRoundsUpAndErrorsKeepTheLastInstruction() {
        val guiding = sampleGuiding()
        val state = guiding.copy(guidance = guiding.guidance.copy(
            remainingDuration = Duration.ofMillis(60_001),
            remainingDistanceMeters = 1_234.0,
        ))
        val standard = requireNotNull(navHudVisualFor(state, noFix = false))
        assertEquals("残り 1.23km・2分", standard.secondary)

        val noFix = requireNotNull(navHudVisualFor(state, noFix = true))
        assertEquals(HudDimmed, noFix.primaryColor)
        assertEquals(HudDimmed, noFix.secondaryColor)
        assertEquals(0.3f, noFix.route!!.opacity, 0f)
        assertEquals(0.6f, standard.route!!.opacity, 0f)

        val failed = requireNotNull(navHudVisualFor(state.copy(rerouteError = NavError.NETWORK), noFix = false))
        assertEquals(standard.primary, failed.primary)
        assertEquals("通信できません", failed.secondary)
        assertEquals(HudDimmed, failed.secondaryColor)

        val privateInstructions = state.copy(guidance = state.guidance.copy(
            nextTurn = turn("UNKNOWN", 30.0, "合成施設名"),
        ))
        val fallback = requireNotNull(navHudVisualFor(privateInstructions, noFix = false))
        assertTrue("合成施設名" in fallback.primary)
        assertEquals("［案内文省略］", fallback.primaryForLog)
        assertNull(navHudVisualFor(NavState.Arrived(state.destination), noFix = false)?.secondary)
    }

    @Test
    fun routeStartsAtTheCurrentProjectionAndExcludesPassedSegments() {
        val corner = NavPoint(0.001, 0.0)
        val afterTurn = NavPoint(0.001, 0.001)
        val destination = NavPoint(0.001, 0.002)
        val route = NavRoute(300, Duration.ofMinutes(3), listOf(start, corner, destination), emptyList())
        val calculator = GuidanceCalculator(route, destination)
        assertEquals(listOf(start, corner, destination), calculator.update(start).remainingPolyline)
        val remaining = calculator.update(afterTurn).remainingPolyline
        assertEquals(2, remaining.size)
        assertEquals(afterTurn.latitude, remaining.first().latitude, 1e-9)
        assertEquals(afterTurn.longitude, remaining.first().longitude, 1e-9)
        assertEquals(destination, remaining.last())
    }

    private fun turn(maneuver: String, distance: Double, instructions: String = "") =
        NavTurn(NavStep(start, end, 30, listOf(start, end), maneuver, instructions), distance, null)
}

internal fun sampleGuiding(): NavState.Guiding {
    val route = syntheticNavRoute()
    val destination = NavDestination(route.polyline.last())
    return NavState.Guiding(destination, route, GuidanceCalculator(route, destination.point).update(route.polyline.first()))
}
