package com.example.data.protocol

import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject

object CaravanProtocol {
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 32 * 1024

    fun buildJoin(
        clientId: String,
        displayName: String,
        inviteCode: String,
        carName: String? = null,
        avatarColor: String? = null,
        leaderToken: String? = null
    ): String {
        val json = JSONObject().apply {
            put("type", "join")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("clientId", clientId)
            put("displayName", displayName)
            put("inviteCode", inviteCode)
            if (!carName.isNullOrBlank()) put("carName", carName)
            if (!avatarColor.isNullOrBlank()) put("avatarColor", avatarColor)
            if (!leaderToken.isNullOrBlank()) put("leaderToken", leaderToken)
        }
        return json.toString()
    }

    fun buildLocationUpdate(
        latitude: Double,
        longitude: Double,
        accuracy: Double? = null,
        speed: Double? = null,
        heading: Double? = null
    ): String {
        val json = JSONObject().apply {
            put("type", "location_update")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("latitude", latitude)
            put("longitude", longitude)
            if (accuracy != null) put("accuracy", accuracy)
            if (speed != null) put("speed", speed)
            if (heading != null) put("heading", heading)
        }
        return json.toString()
    }

    fun buildDestinationUpdate(
        latitude: Double,
        longitude: Double,
        leaderToken: String,
        label: String? = null,
        colorHex: String? = null
    ): String {
        val json = JSONObject().apply {
            put("type", "destination_update")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("latitude", latitude)
            put("longitude", longitude)
            put("leaderToken", leaderToken)
            if (!label.isNullOrBlank()) put("label", label)
            if (!colorHex.isNullOrBlank()) put("colorHex", colorHex)
        }
        return json.toString()
    }

    fun buildMapMark(
        latitude: Double,
        longitude: Double,
        color: String
    ): String {
        return JSONObject().apply {
            put("type", "map_mark")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("latitude", latitude)
            put("longitude", longitude)
            put("color", color)
        }.toString()
    }

    fun buildMapMarkClear(): String {
        return JSONObject().apply {
            put("type", "map_mark_clear")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun buildRouteUpdate(
        points: List<LatLngPoint>,
        colorHex: String
    ): String {
        val maxPoints = 200
        val sampledPoints = if (points.size <= maxPoints) {
            points
        } else {
            val step = (points.size - 1).toDouble() / (maxPoints - 1)
            val result = mutableListOf<LatLngPoint>()
            for (i in 0 until maxPoints - 1) {
                val index = (i * step).toInt().coerceIn(0, points.size - 1)
                result.add(points[index])
            }
            result.add(points.last())
            result
        }

        val pointsArr = JSONArray()
        sampledPoints.forEach { pt ->
            val ptArr = JSONArray()
            ptArr.put(pt.latitude)
            ptArr.put(pt.longitude)
            pointsArr.put(ptArr)
        }
        return JSONObject().apply {
            put("type", "route_update")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("colorHex", colorHex)
            put("points", pointsArr)
        }.toString()
    }

    fun buildRouteClear(): String {
        return JSONObject().apply {
            put("type", "route_clear")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun buildChatMessage(text: String): String {
        val json = JSONObject().apply {
            put("type", "chat_message")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("text", text)
        }
        return json.toString()
    }

    fun buildPttRequest(): String {
        return JSONObject().apply {
            put("type", "ptt_request")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun buildPttRelease(): String {
        return JSONObject().apply {
            put("type", "ptt_release")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun buildAudioChunk(dataBase64: String, sampleRate: Int = 16000, seq: Int = 0): String {
        return JSONObject().apply {
            put("type", "audio_chunk")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
            put("data", dataBase64)
            put("sampleRate", sampleRate)
            put("seq", seq)
        }.toString()
    }

    fun buildPing(): String {
        return JSONObject().apply {
            put("type", "ping")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun buildLeave(): String {
        return JSONObject().apply {
            put("type", "leave")
            put("version", VERSION)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun parseMember(json: JSONObject, defaultLeaderId: String? = null): TripMember {
        val id = json.optString("id", json.optString("clientId", ""))
        val isLeaderExplicit = json.optBoolean("isLeader", false)
        val isLeader = isLeaderExplicit || (defaultLeaderId != null && defaultLeaderId == id)
        return TripMember(
            id = id,
            displayName = json.optString("displayName", "Driver"),
            carName = if (json.has("carName")) json.optString("carName") else null,
            avatarColor = if (json.has("avatarColor")) json.optString("avatarColor") else "#0EA5E9",
            latitude = if (json.has("latitude") && !json.isNull("latitude")) json.optDouble("latitude") else null,
            longitude = if (json.has("longitude") && !json.isNull("longitude")) json.optDouble("longitude") else null,
            accuracy = if (json.has("accuracy") && !json.isNull("accuracy")) json.optDouble("accuracy") else null,
            speed = if (json.has("speed") && !json.isNull("speed")) json.optDouble("speed") else null,
            heading = if (json.has("heading") && !json.isNull("heading")) json.optDouble("heading") else null,
            lastLocationAt = if (json.has("lastLocationAt") && !json.isNull("lastLocationAt")) json.optLong("lastLocationAt") else null,
            lastSeenAt = json.optLong("lastSeenAt", System.currentTimeMillis()),
            connectionStatus = when (json.optString("connectionStatus")) {
                "reconnecting" -> MemberConnectionStatus.RECONNECTING
                "offline" -> MemberConnectionStatus.OFFLINE
                else -> MemberConnectionStatus.CONNECTED
            },
            isLeader = isLeader
        )
    }

    fun parseMembersList(array: JSONArray, leaderId: String?): List<TripMember> {
        val list = mutableListOf<TripMember>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj != null) {
                list.add(parseMember(obj, leaderId))
            }
        }
        return list
    }

    fun parseDestination(json: JSONObject): TripDestination? {
        val target = if (json.has("destination") && !json.isNull("destination")) {
            json.optJSONObject("destination") ?: json
        } else {
            json
        }
        val lat = target.optDouble("latitude", Double.NaN)
        val lng = target.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null

        return TripDestination(
            latitude = lat.coerceIn(-85.0, 85.0),
            longitude = lng.coerceIn(-180.0, 180.0),
            label = target.optString("label", "Destination"),
            updatedAt = target.optLong("updatedAt", System.currentTimeMillis()),
            updatedById = target.optString("updatedById", ""),
            updatedByName = target.optString("updatedByName", ""),
            colorHex = if (target.has("colorHex") && !target.isNull("colorHex")) target.optString("colorHex") else null
        )
    }

    fun parseMapMark(json: JSONObject): MapMark? {
        val target = if (json.has("mark") && !json.isNull("mark")) {
            json.optJSONObject("mark") ?: json
        } else {
            json
        }
        val lat = target.optDouble("latitude", Double.NaN)
        val lng = target.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null

        return MapMark(
            clientId = target.optString("clientId", ""),
            displayName = target.optString("displayName", "Driver"),
            latitude = lat.coerceIn(-85.0, 85.0),
            longitude = lng.coerceIn(-180.0, 180.0),
            color = target.optString("color", "#0EA5E9"),
            updatedAt = target.optLong("updatedAt", target.optLong("timestamp", System.currentTimeMillis()))
        )
    }

    fun parseSharedRoute(json: JSONObject): SharedRoute {
        val target = if (json.has("route") && !json.isNull("route")) {
            json.optJSONObject("route") ?: json
        } else {
            json
        }
        val clientId = target.optString("clientId", "")
        val colorHex = target.optString("colorHex", "#2563EB")
        val points = mutableListOf<LatLngPoint>()
        val arr = target.optJSONArray("points")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val ptArr = arr.optJSONArray(i)
                if (ptArr != null && ptArr.length() >= 2) {
                    val lat = ptArr.optDouble(0, Double.NaN)
                    val lng = ptArr.optDouble(1, Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN()) {
                        points.add(LatLngPoint(lat.coerceIn(-85.0, 85.0), lng.coerceIn(-180.0, 180.0)))
                    }
                } else {
                    val ptObj = arr.optJSONObject(i)
                    if (ptObj != null) {
                        val lat = ptObj.optDouble("latitude", Double.NaN)
                        val lng = ptObj.optDouble("longitude", Double.NaN)
                        if (!lat.isNaN() && !lng.isNaN()) {
                            points.add(LatLngPoint(lat.coerceIn(-85.0, 85.0), lng.coerceIn(-180.0, 180.0)))
                        }
                    }
                }
            }
        }
        return SharedRoute(
            clientId = clientId,
            colorHex = colorHex,
            points = points
        )
    }
}
