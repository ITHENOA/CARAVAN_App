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
}
