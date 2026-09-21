package com.example.data.network

import com.example.data.model.LatLngPoint
import com.example.data.model.RouteSegment
import com.example.util.ConvoyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class CreateTripResult(
    val tripId: String,
    val inviteCode: String,
    val leaderToken: String,
    val leaderId: String,
    val name: String,
    val joinUrl: String,
    val qrPayload: String
)

data class RouteResult(
    val points: List<LatLngPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Per-step traffic colors from Neshan; empty for solid user-colored OSRM routes. */
    val segments: List<com.example.data.model.RouteSegment> = emptyList()
)

class CaravanApiClient(
    private var baseUrl: String = "https://caravan-backend.ithenoa.workers.dev"
) {
    private var proxy: Proxy? = null
    private var dns: okhttp3.Dns = okhttp3.Dns.SYSTEM
    private var client = buildClient()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun updateBaseUrl(newBaseUrl: String) {
        baseUrl = newBaseUrl.trimEnd('/')
    }

    fun updateDns(dns: okhttp3.Dns) {
        this.dns = dns
        client = buildClient()
    }

    /**
     * @param enabled custom proxy; when false, OkHttp uses the JVM/system [java.net.ProxySelector].
     * Uses [InetSocketAddress.createUnresolved] so host lookup never runs on the UI thread.
     */
    fun updateProxy(
        enabled: Boolean,
        host: String,
        port: Int,
        type: Proxy.Type = Proxy.Type.HTTP
    ) {
        try {
            proxy = if (enabled && host.isNotBlank() && port in 1..65535) {
                Proxy(type, InetSocketAddress.createUnresolved(host.trim(), port))
            } else {
                null
            }
            client = buildClient()
        } catch (e: Exception) {
            Log.e("CaravanApi", "updateProxy failed; falling back to system proxy", e)
            proxy = null
            client = buildClient()
        }
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(dns)
        if (proxy != null) builder.proxy(proxy)
        return builder.build()
    }

    suspend fun createTrip(
        name: String,
        displayName: String,
        clientId: String
    ): Result<CreateTripResult> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("name", name)
                put("displayName", displayName)
                put("clientId", clientId)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/trips")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to create trip on server: ${response.code} $bodyStr")
                )
            }

            val resObj = JSONObject(bodyStr)
            Result.success(
                CreateTripResult(
                    tripId = resObj.getString("tripId"),
                    inviteCode = resObj.getString("inviteCode"),
                    leaderToken = resObj.optString("leaderToken", "leader-secret"),
                    leaderId = resObj.optString("leaderId", clientId),
                    name = resObj.optString("name", name),
                    joinUrl = resObj.optString("joinUrl", ""),
                    qrPayload = resObj.optString("qrPayload", "")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun lookupTrip(inviteCode: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("inviteCode", inviteCode.trim())
            }
            val request = Request.Builder()
                .url("$baseUrl/api/trips/lookup")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Invite code not found on server: ${response.code}")
                )
            }

            val resObj = JSONObject(bodyStr)
            val tripId = resObj.optString("tripId", "")
            if (tripId.isNotEmpty()) {
                Result.success(tripId)
            } else {
                Result.failure(Exception("Trip ID not returned from server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun calculateNeshanRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        apiKey: String
    ): RouteResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty()) {
            try {
                val url = "https://api.neshan.org/v4/direction?type=car&origin=$startLat,$startLng&destination=$endLat,$endLng"
                val request = Request.Builder()
                    .url(url)
                    .header("Api-Key", trimmedKey)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val routes = json.optJSONArray("routes")
                    if (routes != null && routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val legs = route.optJSONArray("legs")
                        var totalDist = 0.0
                        var totalDur = 0.0
                        val allPoints = mutableListOf<LatLngPoint>()
                        val builtSegments = mutableListOf<RouteSegment>()

                        if (legs != null && legs.length() > 0) {
                            for (l in 0 until legs.length()) {
                                val leg = legs.getJSONObject(l)
                                val dObj = leg.optJSONObject("distance")
                                if (dObj != null) totalDist += dObj.optDouble("value", 0.0)
                                val durObj = leg.optJSONObject("duration")
                                if (durObj != null) totalDur += durObj.optDouble("value", 0.0)

                                val steps = leg.optJSONArray("steps")
                                if (steps != null) {
                                    for (s in 0 until steps.length()) {
                                        val step = steps.getJSONObject(s)
                                        val poly = step.optString("polyline", "")
                                        if (poly.isEmpty()) continue
                                        val decoded = decodePolyline(poly)
                                        if (decoded.size < 2) continue

                                        val stepDist = step.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
                                        val stepDur = step.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
                                        val speedMps = if (stepDur > 0 && stepDist > 0) stepDist / stepDur else Double.NaN
                                        // Neshan has no congestion labels — estimate traffic tint from implied speed
                                        val color = ConvoyUtils.trafficColorHex(
                                            if (speedMps.isNaN()) null else speedMps
                                        )

                                        if (builtSegments.isNotEmpty() &&
                                            builtSegments.last().colorHex.equals(color, ignoreCase = true)
                                        ) {
                                            val merged = builtSegments.last().points.toMutableList()
                                            val start = decoded.first()
                                            val last = merged.last()
                                            val skipFirst =
                                                last.latitude == start.latitude && last.longitude == start.longitude
                                            merged.addAll(if (skipFirst) decoded.drop(1) else decoded)
                                            builtSegments[builtSegments.lastIndex] =
                                                RouteSegment(points = merged, colorHex = color)
                                        } else {
                                            builtSegments.add(RouteSegment(points = decoded, colorHex = color))
                                        }

                                        if (allPoints.isEmpty()) {
                                            allPoints.addAll(decoded)
                                        } else {
                                            val start = decoded.first()
                                            val last = allPoints.last()
                                            val skipFirst =
                                                last.latitude == start.latitude && last.longitude == start.longitude
                                            allPoints.addAll(if (skipFirst) decoded.drop(1) else decoded)
                                        }
                                    }
                                }
                            }
                        }

                        if (allPoints.isNotEmpty()) {
                            return@withContext RouteResult(
                                points = allPoints,
                                distanceMeters = totalDist,
                                durationSeconds = totalDur,
                                segments = builtSegments
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to standard routing
            }
        }
        // Fallback to standard OSRM routing (no traffic segments)
        calculateRoute(startLat, startLng, endLat, endLng)
    }

    private fun decodePolyline(encoded: String): List<LatLngPoint> {
        val poly = ArrayList<LatLngPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLngPoint(latitude = lat.toDouble() / 1E5, longitude = lng.toDouble() / 1E5))
        }
        return poly
    }

    suspend fun calculateRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): RouteResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=geojson"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrBlank()) {
                val json = JSONObject(body)
                val routes = json.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val first = routes.getJSONObject(0)
                    val distance = first.optDouble("distance", 0.0)
                    val duration = first.optDouble("duration", 0.0)
                    val geometry = first.getJSONObject("geometry")
                    val coords = geometry.getJSONArray("coordinates")
                    val points = mutableListOf<LatLngPoint>()
                    for (i in 0 until coords.length()) {
                        val c = coords.getJSONArray(i)
                        points.add(LatLngPoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
                    }
                    if (points.isNotEmpty()) {
                        return@withContext RouteResult(points, distance, duration)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback interpolation
        }

        // Direct interpolation fallback with intermediate curved waypoints
        val distM = com.example.util.ConvoyUtils.distanceMeters(startLat, startLng, endLat, endLng)
        val estSecs = if (distM > 0) (distM / 15.0) else 0.0 // assume ~54 km/h average
        val steps = 20
        val points = mutableListOf<LatLngPoint>()
        for (i in 0..steps) {
            val t = i / steps.toDouble()
            // add gentle curve
            val lat = startLat + (endLat - startLat) * t + Math.sin(t * Math.PI) * 0.002
            val lng = startLng + (endLng - startLng) * t + Math.sin(t * Math.PI) * 0.003
            points.add(LatLngPoint(lat, lng))
        }
        RouteResult(points, distM, estSecs)
    }

    suspend fun searchPlaces(
        context: android.content.Context,
        query: String,
        userLat: Double,
        userLng: Double
    ): List<SearchPlaceItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val results = mutableListOf<SearchPlaceItem>()
        val hasUserLoc = (userLat != 0.0 || userLng != 0.0) && !userLat.isNaN() && !userLng.isNaN()

        // 1. Android Geocoder
        try {
            if (android.location.Geocoder.isPresent()) {
                val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(trimmed, 8)
                addresses?.forEach { addr ->
                    val name = addr.featureName ?: addr.thoroughfare ?: addr.locality ?: trimmed
                    val details = listOfNotNull(
                        addr.thoroughfare,
                        addr.subLocality,
                        addr.locality,
                        addr.adminArea,
                        addr.countryName
                    ).distinct().joinToString(", ")
                    val dist = if (hasUserLoc) {
                        val d = FloatArray(1)
                        android.location.Location.distanceBetween(userLat, userLng, addr.latitude, addr.longitude, d)
                        d[0]
                    } else null
                    results.add(
                        SearchPlaceItem(
                            name = name,
                            address = if (details.isNotBlank()) details else "${addr.latitude}, ${addr.longitude}",
                            latitude = addr.latitude,
                            longitude = addr.longitude,
                            distanceMeters = dist
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("PlaceSearch", "Geocoder failed: ${e.message}")
        }

        // 2. OpenStreetMap Nominatim Fallback
        if (results.size < 4) {
            try {
                val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=10&addressdetails=1"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "CaravanApp/3.2 (Android; Caravan Convoy Navigation)")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val array = org.json.JSONArray(body)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val displayName = obj.optString("display_name", "")
                        val lat = obj.optDouble("lat", 0.0)
                        val lon = obj.optDouble("lon", 0.0)
                        if (lat != 0.0 && lon != 0.0) {
                            val parts = displayName.split(",")
                            val title = parts.firstOrNull()?.trim() ?: trimmed
                            val desc = parts.drop(1).joinToString(",").trim()
                            val dist = if (hasUserLoc) {
                                val d = FloatArray(1)
                                android.location.Location.distanceBetween(userLat, userLng, lat, lon, d)
                                d[0]
                            } else null
                            if (results.none { Math.abs(it.latitude - lat) < 0.0005 && Math.abs(it.longitude - lon) < 0.0005 }) {
                                results.add(
                                    SearchPlaceItem(
                                        name = title,
                                        address = if (desc.isNotBlank()) desc else displayName,
                                        latitude = lat,
                                        longitude = lon,
                                        distanceMeters = dist
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PlaceSearch", "Nominatim search failed: ${e.message}")
            }
        }

        if (hasUserLoc) {
            results.sortedBy { it.distanceMeters ?: Float.MAX_VALUE }
        } else {
            results
        }
    }
}

data class SearchPlaceItem(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float? = null
)
