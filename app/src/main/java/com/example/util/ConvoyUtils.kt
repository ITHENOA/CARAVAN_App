package com.example.util

import com.example.data.model.TripMember
import kotlin.math.*

object ConvoyUtils {

    val palette = listOf(
        "#0EA5E9", // Sky Cyan
        "#F59E0B", // Amber
        "#EF4444", // Crimson Red
        "#8B5CF6", // Purple
        "#EC4899", // Pink
        "#14B8A6", // Teal
        "#F97316", // Orange
        "#6366F1", // Indigo
        "#84CC16", // Lime
        "#06B6D4", // Cyan
        "#D946EF", // Magenta
        "#3B82F6", // Cobalt Blue
        "#E11D48", // Rose
        "#10B981"  // Emerald Green
    )

    fun colorForClientId(clientId: String, preferred: String? = null): String {
        if (!preferred.isNullOrBlank() && preferred.startsWith("#")) {
            return preferred
        }
        val hash = abs(clientId.hashCode())
        return palette[hash % palette.size]
    }

    /**
     * Guarantees every person in the convoy has a strictly unique, distinct color.
     */
    fun resolveDistinctColors(
        selfId: String,
        selfPreferredColor: String,
        members: List<TripMember>
    ): Pair<String, List<TripMember>> {
        val takenColors = mutableSetOf<String>()
        val normalize = { c: String? ->
            val str = (c ?: "").trim().uppercase()
            if (str.isEmpty()) "" else if (str.startsWith("#")) str else "#$str"
        }

        // 1. Assign unique color for self
        val prefNorm = normalize(selfPreferredColor)
        val selfColor = if (prefNorm.length == 7 && !takenColors.contains(prefNorm)) {
            prefNorm
        } else {
            palette.firstOrNull { !takenColors.contains(it) } ?: palette[0]
        }
        takenColors.add(selfColor)

        // 2. Assign unique color for each member
        val updatedMembers = members.map { member ->
            val mPref = normalize(member.avatarColor)
            val assignedColor = if (mPref.length == 7 && !takenColors.contains(mPref)) {
                mPref
            } else {
                val freeColor = palette.firstOrNull { !takenColors.contains(it) }
                if (freeColor != null) {
                    freeColor
                } else {
                    val hash = abs(member.id.hashCode())
                    palette[hash % palette.size]
                }
            }
            takenColors.add(assignedColor)
            if (member.avatarColor != assignedColor) {
                member.copy(avatarColor = assignedColor)
            } else {
                member
            }
        }

        return Pair(selfColor, updatedMembers)
    }

    fun isValidLatLng(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (lat.isNaN() || lng.isNaN()) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    /**
     * Haversine distance in meters between two coordinates.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun calculateRouteDistanceMeters(points: List<com.example.data.model.LatLngPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i].latitude, points[i].longitude, points[i + 1].latitude, points[i + 1].longitude)
        }
        return total
    }

    fun formatDistance(meters: Double, useMetric: Boolean = true): String {
        if (meters.isNaN() || meters < 0) return "--"
        return if (useMetric) {
            if (meters < 1000) {
                "${meters.roundToInt()} m"
            } else {
                String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
            }
        } else {
            val feet = meters * 3.28084
            if (feet < 1000) {
                "${feet.roundToInt()} ft"
            } else {
                val miles = meters * 0.000621371
                String.format(java.util.Locale.US, "%.1f mi", miles)
            }
        }
    }

    fun formatSpeed(metersPerSec: Double?, useMetric: Boolean = true): String {
        if (metersPerSec == null || metersPerSec.isNaN() || metersPerSec < 0) return "0 km/h"
        return if (useMetric) {
            val kmh = (metersPerSec * 3.6).roundToInt()
            "$kmh km/h"
        } else {
            val mph = (metersPerSec * 2.23694).roundToInt()
            "$mph mph"
        }
    }

    fun formatEta(seconds: Double): String {
        if (seconds <= 0 || seconds.isNaN()) return "--"
        val totalMinutes = (seconds / 60.0).roundToInt()
        if (totalMinutes < 60) return "$totalMinutes min"
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
    }

    fun speedColorHex(metersPerSec: Double?): String {
        if (metersPerSec == null || metersPerSec.isNaN()) return "#9E9E9E"
        val kmh = metersPerSec * 3.6
        return when {
            kmh < 15 -> "#EF4444" // Slow / Stopped
            kmh < 45 -> "#F59E0B" // Moderate / Traffic
            else -> "#10B981"     // Free flowing
        }
    }

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val lambdaDiff = Math.toRadians(lon2 - lon1)
        val y = sin(lambdaDiff) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(lambdaDiff)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    /**
     * Formats relative time string for member location updates (e.g. "2m ago", "15m ago", "1h ago").
     * Returns null if fresh (< 45 seconds).
     */
    fun formatTimeAgo(timestamp: Long?): String? {
        if (timestamp == null || timestamp <= 0L) return null
        val diffMs = System.currentTimeMillis() - timestamp
        if (diffMs < 45_000L) return null // Recent (< 45 sec), no stale text needed
        val minutes = (diffMs / 60_000L).toInt()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
    }

    /**
     * Calculates minimum distance in meters from a coordinate (lat, lng)
     * to a polyline consisting of LatLngPoint coordinates.
     */
    fun minDistanceToPolyline(lat: Double, lng: Double, points: List<com.example.data.model.LatLngPoint>): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        if (points.size == 1) return distanceMeters(lat, lng, points[0].latitude, points[0].longitude)

        var minDistance = Double.MAX_VALUE
        val latRad = Math.toRadians(lat)
        val cosLat = cos(latRad)
        val metersPerDegLat = 111132.95
        val metersPerDegLng = 111412.84 * cosLat

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            // Convert to local meter coordinates relative to (lat, lng)
            val x1 = (p1.longitude - lng) * metersPerDegLng
            val y1 = (p1.latitude - lat) * metersPerDegLat
            val x2 = (p2.longitude - lng) * metersPerDegLng
            val y2 = (p2.latitude - lat) * metersPerDegLat

            val dx = x2 - x1
            val dy = y2 - y1
            val lenSq = dx * dx + dy * dy

            val dist = if (lenSq == 0.0) {
                sqrt(x1 * x1 + y1 * y1)
            } else {
                // Project (0,0) onto segment [p1, p2]
                val t = (-(x1 * dx + y1 * dy) / lenSq).coerceIn(0.0, 1.0)
                val projX = x1 + t * dx
                val projY = y1 + t * dy
                sqrt(projX * projX + projY * projY)
            }

            if (dist < minDistance) {
                minDistance = dist
            }
        }
        return minDistance
    }
}
