package io.github.yamichi77.aurawalkinfo.nav

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NavApiTest {
    private val origin = NavPoint(0.0, 0.0)
    private val destination = NavPoint(0.001, 0.0)
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val preferences = app.getSharedPreferences("test_nav_quota", Context.MODE_PRIVATE)

    @Test
    fun sendsExactPlacesRequestAndParsesCandidates() = runTest {
        val service = api { request ->
            assertEquals("https://places.googleapis.com/v1/places:searchText", request.url)
            assertHeaders(request, "places.id,places.displayName,places.location,places.formattedAddress")
            val body = JSONObject(request.body)
            assertEquals(setOf("textQuery", "languageCode", "regionCode", "pageSize", "locationBias"), body.keys().asSequence().toSet())
            assertEquals("合成の施設", body.getString("textQuery"))
            assertEquals("ja", body.getString("languageCode"))
            assertEquals("JP", body.getString("regionCode"))
            assertEquals(5, body.getInt("pageSize"))
            val circle = body.getJSONObject("locationBias").getJSONObject("circle")
            assertEquals(5_000, circle.getInt("radius"))
            assertPoint(body = circle.getJSONObject("center"), point = origin)
            NavResponse(200, """
                {"places":[{
                    "id":"test-place", "displayName":{"text":"合成の施設"}, "formattedAddress":"架空市",
                    "location":{"latitude":0.001,"longitude":0}
                }]}
            """.trimIndent())
        }
        val place = service.search("合成の施設", origin).single()
        assertEquals(NavCandidate("test-place", "合成の施設", "架空市", destination, origin.distanceTo(destination)), place)
        assertEquals(111.195, place.distanceMeters, 0.01)
    }

    @Test
    fun sendsOnlyWalkingRouteFieldsAndParsesGeometryAndInstructions() = runTest {
        val service = api { request ->
            assertEquals("https://routes.googleapis.com/directions/v2:computeRoutes", request.url)
            assertHeaders(
                request,
                "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
                    "routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline," +
                    "routes.legs.steps.navigationInstruction,routes.legs.steps.startLocation,routes.legs.steps.endLocation",
            )
            val body = JSONObject(request.body)
            assertEquals(setOf("origin", "destination", "travelMode", "languageCode", "units"), body.keys().asSequence().toSet())
            assertEquals("WALK", body.getString("travelMode"))
            assertEquals("ja-JP", body.getString("languageCode"))
            assertEquals("METRIC", body.getString("units"))
            for ((key, point) in listOf("origin" to origin, "destination" to destination)) {
                val waypoint = body.getJSONObject(key)
                assertEquals(setOf("location"), waypoint.keys().asSequence().toSet())
                val location = waypoint.getJSONObject("location")
                assertEquals(setOf("latLng"), location.keys().asSequence().toSet())
                assertPoint(location.getJSONObject("latLng"), point)
            }
            NavResponse(200, """
                {"routes":[{
                    "distanceMeters":111, "duration":"87.5s", "polyline":{"encodedPolyline":"??gE?"},
                    "legs":[{"steps":[{
                        "distanceMeters":111, "polyline":{"encodedPolyline":"??gE?"},
                        "navigationInstruction":{"maneuver":"TURN_LEFT","instructions":"左に曲がる"},
                        "startLocation":{"latLng":{}}, "endLocation":{"latLng":{"latitude":0.001}}
                    }]}]
                }]}
            """.trimIndent())
        }
        val route = service.route(origin, destination)
        assertEquals(111, route.distanceMeters)
        assertEquals(Duration.ofMillis(87_500), route.duration)
        assertEquals(listOf(origin, destination), route.polyline)
        val step = route.steps.single()
        assertEquals(route.polyline, step.polyline)
        assertEquals(origin, step.start)
        assertEquals(destination, step.end)
        assertEquals(111, step.distanceMeters)
        assertEquals(NavDirection.LEFT, step.direction)
        assertEquals("左に曲がる", step.instructions)
    }

    @Test
    fun distinguishesEmptyResultsCredentialErrorsTransportFailureAndMalformedResponses() = runTest {
        assertEquals(NavError.NO_RESULTS, failure { api { NavResponse(200, """{"places":[]}""") }.search("test", origin) })
        assertEquals(NavError.NO_RESULTS, failure { api { NavResponse(200, "{}") }.route(origin, destination) })
        assertEquals(NavError.PERMISSION, failure {
            api { NavResponse(403, """{"error":{"status":"PERMISSION_DENIED"}}""") }.route(origin, destination)
        })
        assertEquals(NavError.PERMISSION, failure {
            api { NavResponse(400, """{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""") }
                .search("test", origin)
        })
        assertEquals(NavError.NETWORK, failure { api { throw IOException() }.route(origin, destination) })
        assertEquals(NavError.OTHER, failure { api { NavResponse(503, "unavailable") }.route(origin, destination) })
        assertEquals(NavError.OTHER, failure { api { NavResponse(200, """{"places":[{}]}""") }.search("test", origin) })
    }

    @Test
    fun dailyLimitsPersistAcrossInstancesAndResetOnTheNextLocalDate() = runTest {
        var date = LocalDate.of(2026, 1, 1)
        fun quota() = DailyNavQuota(preferences) { date }
        var posts = 0
        val response = NavTransport { posts++; NavResponse(200, "{}") }
        val first = api(quota(), response)
        repeat(150) { assertEquals(NavError.NO_RESULTS, failure { first.search("test", origin) }) }
        val reopened = api(quota(), response)
        assertEquals(NavError.DAILY_LIMIT, failure { reopened.search("test", origin) })
        assertEquals(150, posts)

        repeat(300) { assertEquals(NavError.NO_RESULTS, failure { reopened.route(origin, destination) }) }
        assertEquals(NavError.DAILY_LIMIT, failure { reopened.route(origin, destination) })
        assertEquals(450, posts)

        date = date.plusDays(1)
        assertEquals(NavError.NO_RESULTS, failure { reopened.search("test", origin) })
        assertEquals(NavError.NO_RESULTS, failure { reopened.route(origin, destination) })
        assertEquals(452, posts)
    }

    private fun api(quota: DailyNavQuota = DailyNavQuota(preferences), transport: NavTransport) =
        NavApi({ NavCredentials("unit-test-placeholder", "example.test", "A1".repeat(20)) }, quota, transport, Dispatchers.Unconfined)

    private fun assertHeaders(request: NavRequest, mask: String) {
        assertEquals(mask, request.headers["X-Goog-FieldMask"])
        assertEquals("unit-test-placeholder", request.headers["X-Goog-Api-Key"])
        assertEquals("example.test", request.headers["X-Android-Package"])
        assertTrue(request.headers.getValue("X-Android-Cert").matches(Regex("[A-F0-9]{40}")))
    }

    private fun assertPoint(body: JSONObject, point: NavPoint) {
        assertEquals(setOf("latitude", "longitude"), body.keys().asSequence().toSet())
        assertEquals(point.latitude, body.getDouble("latitude"), 0.0)
        assertEquals(point.longitude, body.getDouble("longitude"), 0.0)
    }

    private suspend fun failure(call: suspend () -> Any): NavError {
        try {
            call()
        } catch (failure: NavFailure) {
            return failure.reason
        }
        error("Expected a typed navigation failure")
    }
}
