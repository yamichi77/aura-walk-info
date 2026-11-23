package io.github.yamichi77.aurawalkinfo.nav

import java.io.IOException
import java.time.Duration
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal class NavFailure(val reason: NavError) : Exception()

internal interface NavService {
    suspend fun search(query: String, origin: NavPoint): List<NavCandidate>
    suspend fun route(origin: NavPoint, destination: NavPoint): NavRoute
}

internal enum class NavEndpoint(val url: String, val fieldMask: String, val dailyLimit: Int) {
    PLACES(
        "https://places.googleapis.com/v1/places:searchText",
        "places.id,places.displayName,places.location,places.formattedAddress",
        150,
    ),
    ROUTES(
        "https://routes.googleapis.com/directions/v2:computeRoutes",
        "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
            "routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline," +
            "routes.legs.steps.navigationInstruction,routes.legs.steps.startLocation,routes.legs.steps.endLocation",
        300,
    ),
}

internal class NavCredentials(private val key: String, private val packageName: String, private val certSha1: String) {
    fun request(endpoint: NavEndpoint, body: JSONObject) = NavRequest(
        endpoint.url,
        mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "X-Goog-Api-Key" to key,
            "X-Goog-FieldMask" to endpoint.fieldMask,
            "X-Android-Package" to packageName,
            "X-Android-Cert" to certSha1,
        ),
        body.toString(),
    )
}

internal class NavRequest(val url: String, val headers: Map<String, String>, val body: String)
internal class NavResponse(val status: Int, val body: String)
internal fun interface NavTransport {
    suspend fun post(request: NavRequest): NavResponse
}

internal class NavApi(
    private val credentials: () -> NavCredentials,
    private val quota: DailyNavQuota,
    private val transport: NavTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NavService {
    override suspend fun search(query: String, origin: NavPoint): List<NavCandidate> =
        call(NavEndpoint.PLACES, placesBody(query, origin)) { parsePlaces(it, origin) }

    override suspend fun route(origin: NavPoint, destination: NavPoint): NavRoute =
        call(NavEndpoint.ROUTES, routesBody(origin, destination), ::parseRoute)

    private suspend fun <T> call(endpoint: NavEndpoint, body: JSONObject, parse: (JSONObject) -> T): T =
        withContext(io) {
            val request = credentials().request(endpoint, body)
            if (!quota.tryAcquire(endpoint)) throw NavFailure(NavError.DAILY_LIMIT)
            ensureActive()
            val response = try {
                transport.post(request)
            } catch (_: IOException) {
                throw NavFailure(NavError.NETWORK)
            }
            val json = try {
                JSONObject(response.body)
            } catch (_: JSONException) {
                throw NavFailure(response.error(null) ?: NavError.OTHER)
            }
            response.error(json.optJSONObject("error"))?.let { throw NavFailure(it) }
            try {
                parse(json)
            } catch (_: JSONException) {
                throw NavFailure(NavError.OTHER)
            } catch (_: IllegalArgumentException) {
                throw NavFailure(NavError.OTHER)
            } catch (_: DateTimeParseException) {
                throw NavFailure(NavError.OTHER)
            }
        }
}

private fun NavResponse.error(error: JSONObject?): NavError? {
    val statusName = error?.optString("status")
    val details = error?.optJSONArray("details")
    val invalidKey = details != null && (0 until details.length()).any {
        details.optJSONObject(it)?.optString("reason") == "API_KEY_INVALID"
    }
    return when {
        status == 401 || status == 403 || invalidKey ||
            statusName == "UNAUTHENTICATED" || statusName == "PERMISSION_DENIED" -> NavError.PERMISSION
        statusName == "NOT_FOUND" || statusName == "ZERO_RESULTS" -> NavError.NO_RESULTS
        status !in 200..299 || error != null -> NavError.OTHER
        else -> null
    }
}

private fun NavPoint.json() = JSONObject().put("latitude", latitude).put("longitude", longitude)
private fun NavPoint.waypoint() = JSONObject().put("location", JSONObject().put("latLng", json()))

private fun placesBody(query: String, origin: NavPoint) = JSONObject()
    .put("textQuery", query)
    .put("languageCode", "ja")
    .put("regionCode", "JP")
    .put("pageSize", 5)
    .put("locationBias", JSONObject().put("circle", JSONObject().put("center", origin.json()).put("radius", 5_000)))

private fun routesBody(origin: NavPoint, destination: NavPoint) = JSONObject()
    .put("origin", origin.waypoint())
    .put("destination", destination.waypoint())
    .put("travelMode", "WALK")
    .put("languageCode", "ja-JP")
    .put("units", "METRIC")

private fun JSONObject.point() = NavPoint(
    if (has("latitude")) getDouble("latitude") else 0.0,
    if (has("longitude")) getDouble("longitude") else 0.0,
)

private fun parsePlaces(json: JSONObject, origin: NavPoint): List<NavCandidate> {
    val places = json.optJSONArray("places") ?: throw NavFailure(NavError.NO_RESULTS)
    if (places.length() == 0) throw NavFailure(NavError.NO_RESULTS)
    return List(minOf(places.length(), 5)) { index ->
        val place = places.getJSONObject(index)
        val point = place.getJSONObject("location").point()
        NavCandidate(
            id = place.getString("id"),
            name = place.getJSONObject("displayName").getString("text"),
            address = place.optString("formattedAddress"),
            point = point,
            distanceMeters = origin.distanceTo(point),
        )
    }
}

private fun JSONObject.polyline(): List<NavPoint> =
    decodePolyline(optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())

private fun parseRoute(json: JSONObject): NavRoute {
    val response = json.optJSONArray("routes")?.optJSONObject(0) ?: throw NavFailure(NavError.NO_RESULTS)
    val legs = response.getJSONArray("legs")
    val steps = buildList {
        for (legIndex in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(legIndex).getJSONArray("steps")
            for (stepIndex in 0 until legSteps.length()) {
                val step = legSteps.getJSONObject(stepIndex)
                val instruction = step.optJSONObject("navigationInstruction")
                add(
                    NavStep(
                        start = step.getJSONObject("startLocation").getJSONObject("latLng").point(),
                        end = step.getJSONObject("endLocation").getJSONObject("latLng").point(),
                        distanceMeters = step.optInt("distanceMeters"),
                        polyline = step.polyline(),
                        maneuver = instruction?.optString("maneuver").orEmpty(),
                        instructions = instruction?.optString("instructions").orEmpty(),
                    ),
                )
            }
        }
    }
    return NavRoute(
        distanceMeters = response.optInt("distanceMeters"),
        duration = Duration.parse("PT" + response.getString("duration")),
        polyline = response.polyline(),
        steps = steps,
    )
}
