package io.github.yamichi77.aurawalkinfo.nav

import io.github.yamichi77.aurawalkinfo.diagnostics.replay.syntheticFix
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GuidanceCalculatorTest {
    @Test
    fun decodesGooglePolylineExampleAndRejectsTruncation() {
        assertEquals(
            listOf(NavPoint(38.5, -120.2), NavPoint(40.7, -120.95), NavPoint(43.252, -126.453)),
            decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
        )
        assertThrows(IllegalArgumentException::class.java) { decodePolyline("??_") }
    }

    @Test
    fun projectsToRouteAndAdvancesTurnsWithAlongRouteDistanceAndTrueBearings() {
        val start = point(0.0, 0.0)
        val right = point(100.0, 0.0)
        val left = point(100.0, 80.0)
        val end = point(120.0, 80.0)
        val route = NavRoute(
            distanceMeters = 200,
            duration = Duration.ofSeconds(100),
            polyline = listOf(start, end), // Deliberately simplified overview.
            steps = listOf(
                step(start, right, 100, "DEPART"),
                step(right, left, 80, "TURN_RIGHT"),
                step(left, end, 20, "TURN_LEFT"),
            ),
        )
        val calculator = GuidanceCalculator(route, end)

        val northbound = calculator.update(point(60.0, -5.0))
        assertEquals(point(60.0, 0.0).latitude, northbound.positionOnRoute.latitude, 1e-10)
        assertEquals(0.0, northbound.positionOnRoute.longitude, 1e-10)
        assertEquals(5.0, northbound.distanceFromRouteMeters, 0.01)
        assertEquals(60.0, northbound.progressMeters, 0.01)
        assertEquals(140.0, northbound.remainingDistanceMeters, 0.01)
        assertEquals(70.0, northbound.remainingDuration.toMillis() / 1000.0, 0.01)
        val first = checkNotNull(northbound.nextTurn)
        assertEquals(40.0, first.distanceMeters, 0.01)
        assertEquals(7.125, checkNotNull(first.bearingDegrees), 0.01)
        assertEquals(NavDirection.RIGHT, first.step.direction)
        assertEquals("TURN_RIGHT text", first.step.instructions)
        assertEquals(NavDirection.LEFT, calculator.update(right).nextTurn?.step?.direction)

        val eastbound = calculator.update(point(100.0, 20.0))
        val next = checkNotNull(eastbound.nextTurn)
        assertEquals(NavDirection.LEFT, next.step.direction)
        assertEquals(60.0, next.distanceMeters, 0.01)
        assertEquals(90.0, checkNotNull(next.bearingDegrees), 0.01)
        assertEquals(71.565, checkNotNull(eastbound.destinationBearingDegrees), 0.01)
        assertNull(calculator.update(point(110.0, 80.0)).nextTurn)
        assertNull(calculator.update(end).destinationBearingDegrees)
    }

    @Test
    fun usesOverviewWhenStepGeometryIsAbsentAndClassifiesUTurnAndStraight() {
        val start = point(0.0, 0.0)
        val turn = point(100.0, 0.0)
        val end = point(100.0, 100.0)
        val initial = step(start, turn, 100, "STRAIGHT").copy(polyline = emptyList())
        val next = step(turn, end, 100, "UTURN_LEFT").copy(polyline = emptyList())
        val route = NavRoute(200, Duration.ofSeconds(100), listOf(start, turn, end), listOf(initial, next))
        assertEquals(NavDirection.STRAIGHT, initial.direction)
        val guidance = GuidanceCalculator(route, end).update(point(25.0, 0.0))
        val upcoming = checkNotNull(guidance.nextTurn)
        assertEquals(NavDirection.U_TURN, upcoming.step.direction)
        assertEquals(75.0, upcoming.distanceMeters, 0.01)
        assertEquals(0.0, checkNotNull(upcoming.bearingDegrees), 0.01)
    }

    @Test
    fun continuesForwardAfterATurnOnAnOverlappingReturnLeg() {
        val start = point(0.0, 0.0)
        val turn = point(100.0, 0.0)
        val route = NavRoute(
            200,
            Duration.ofSeconds(160),
            listOf(start, turn, start),
            listOf(step(start, turn, 100, "DEPART"), step(turn, start, 100, "UTURN_RIGHT")),
        )
        val calculator = GuidanceCalculator(route, start)
        assertEquals(99.0, calculator.update(point(99.0, 0.0)).progressMeters, 0.01)
        assertEquals(102.0, calculator.update(point(98.0, 0.0)).progressMeters, 0.01)
        assertEquals(120.0, calculator.update(point(80.0, 0.0)).progressMeters, 0.01)
    }

    private fun point(north: Double, east: Double) = syntheticFix(0, northMeters = north, eastMeters = east).navPoint()

    private fun step(start: NavPoint, end: NavPoint, meters: Int, maneuver: String) =
        NavStep(start, end, meters, listOf(start, end), maneuver, "$maneuver text")
}
