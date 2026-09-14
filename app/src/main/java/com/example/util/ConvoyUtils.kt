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

    fun normalizeHex(raw: String?): String {
        var h = (raw ?: "").trim()
        if (h.isEmpty()) return ""
        if (!h.startsWith("#")) h = "#$h"
        if (h.length == 4) {
            // #RGB → #RRGGBB
            h = "#${h[1]}${h[1]}${h[2]}${h[2]}${h[3]}${h[3]}"
        }
        return h.uppercase()
    }

    /**
     * Picks a palette color for [clientId], preferring [preferred] when free.
     * Guarantees uniqueness against [taken] (normalized hex set).
     */
    fun distinctColorFor(
        clientId: String,
        preferred: String? = null,
        taken: Set<String> = emptySet()
    ): String {
        val takenNorm = taken.map { normalizeHex(it) }.filter { it.isNotEmpty() }.toSet()
        val pref = normalizeHex(preferred)
        if (pref.length == 7 && !takenNorm.contains(pref)) return pref

        val ordered = palette.sortedBy { abs(it.hashCode() xor clientId.hashCode()) }
        for (c in ordered) {
            val n = normalizeHex(c)
            if (!takenNorm.contains(n)) return n
        }
        return normalizeHex(palette[abs(clientId.hashCode()) % palette.size])
    }

    fun colorForClientId(clientId: String, preferred: String? = null): String {
        return distinctColorFor(clientId, preferred)
    }

    /**
     * Guarantees every person in the convoy has a strictly unique, distinct color.
     * Self is assigned first; self row in [members] keeps the same resolved color.
     */
    fun resolveDistinctColors(
        selfId: String,
        selfPreferredColor: String,
        members: List<TripMember>
    ): Pair<String, List<TripMember>> {
        val takenColors = mutableSetOf<String>()

        val selfColor = distinctColorFor(selfId, selfPreferredColor, takenColors)
        takenColors.add(selfColor)

        val updatedMembers = members.map { member ->
            if (member.id == selfId) {
                if (normalizeHex(member.avatarColor) == selfColor) member
                else member.copy(avatarColor = selfColor)
            } else {
                val assigned = distinctColorFor(member.id, member.avatarColor, takenColors)
                takenColors.add(assigned)
                if (normalizeHex(member.avatarColor) == assigned) member
                else member.copy(avatarColor = assigned)
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

    /**
     * Traffic tint for Neshan steps inferred from implied speed
     * (Neshan does not return congestion labels): red / orange / green.
     */
    fun trafficColorHex(metersPerSec: Double?): String {
        if (metersPerSec == null || metersPerSec.isNaN()) return "#10B981"
        val kmh = metersPerSec * 3.6
        return when {
            kmh < 12 -> "#EF4444" // Congested
            kmh < 35 -> "#F59E0B" // Moderate
            else -> "#10B981"     // Free flowing
        }
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

    /**
     * Drop the portion of [points] already passed. Returns remaining geometry
     * (from the projection on the route) and the locked vertex index so progress
     * only moves forward (OSRM solid route).
     */
    fun remainingPolyline(
        points: List<com.example.data.model.LatLngPoint>,
        lat: Double,
        lng: Double,
        minVertexIndex: Int = 0
    ): Pair<List<com.example.data.model.LatLngPoint>, Int> {
        if (points.size < 2) return points to 0

        val hit = nearestOnPolyline(points, lat, lng, minVertexIndex) ?: return points to minVertexIndex
        val remaining = ArrayList<com.example.data.model.LatLngPoint>(points.size - hit.vertexIndex)
        remaining.add(hit.projected)
        for (i in hit.vertexIndex + 1 until points.size) {
            val p = points[i]
            val last = remaining.last()
            if (distanceMeters(last.latitude, last.longitude, p.latitude, p.longitude) > 0.5) {
                remaining.add(p)
            }
        }
        return remaining to hit.vertexIndex
    }

    /**
     * Same as [remainingPolyline] but for Neshan traffic-colored stretches.
     * Progress is (segmentIndex, vertexIndex) and only advances forward.
     */
    fun remainingSegments(
        segments: List<com.example.data.model.RouteSegment>,
        lat: Double,
        lng: Double,
        minSegIndex: Int = 0,
        minVertexIndex: Int = 0
    ): Triple<List<com.example.data.model.RouteSegment>, Int, Int> {
        if (segments.isEmpty()) return Triple(emptyList(), 0, 0)

        var bestDist = Double.MAX_VALUE
        var bestSeg = minSegIndex.coerceIn(0, segments.lastIndex)
        var bestVertex = 0
        var bestProj = com.example.data.model.LatLngPoint(lat, lng)

        val startSeg = minSegIndex.coerceIn(0, segments.lastIndex)
        for (s in startSeg until segments.size) {
            val pts = segments[s].points
            if (pts.size < 2) continue
            val fromVertex = if (s == startSeg) minVertexIndex.coerceIn(0, pts.lastIndex - 1) else 0
            val hit = nearestOnPolyline(pts, lat, lng, fromVertex) ?: continue
            if (hit.distanceMeters < bestDist) {
                bestDist = hit.distanceMeters
                bestSeg = s
                bestVertex = hit.vertexIndex
                bestProj = hit.projected
            }
        }

        val out = ArrayList<com.example.data.model.RouteSegment>(segments.size - bestSeg)
        val firstPts = segments[bestSeg].points
        val firstRemaining = ArrayList<com.example.data.model.LatLngPoint>()
        firstRemaining.add(bestProj)
        for (i in bestVertex + 1 until firstPts.size) {
            val p = firstPts[i]
            val last = firstRemaining.last()
            if (distanceMeters(last.latitude, last.longitude, p.latitude, p.longitude) > 0.5) {
                firstRemaining.add(p)
            }
        }
        if (firstRemaining.size >= 2) {
            out.add(segments[bestSeg].copy(points = firstRemaining))
        }
        for (s in bestSeg + 1 until segments.size) {
            val pts = segments[s].points
            if (pts.size >= 2) out.add(segments[s])
        }
        return Triple(out, bestSeg, bestVertex)
    }

    private data class PolylineHit(
        val vertexIndex: Int,
        val projected: com.example.data.model.LatLngPoint,
        val distanceMeters: Double
    )

    /** Closest projection on [points], searching only from [minVertexIndex] forward. */
    private fun nearestOnPolyline(
        points: List<com.example.data.model.LatLngPoint>,
        lat: Double,
        lng: Double,
        minVertexIndex: Int
    ): PolylineHit? {
        if (points.size < 2) return null

        val latRad = Math.toRadians(lat)
        val cosLat = cos(latRad)
        val metersPerDegLat = 111132.95
        val metersPerDegLng = 111412.84 * cosLat

        var bestDist = Double.MAX_VALUE
        var bestIdx = minVertexIndex.coerceIn(0, points.lastIndex - 1)
        var bestT = 0.0

        val from = minVertexIndex.coerceIn(0, points.lastIndex - 1)
        for (i in from until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val x1 = (p1.longitude - lng) * metersPerDegLng
            val y1 = (p1.latitude - lat) * metersPerDegLat
            val x2 = (p2.longitude - lng) * metersPerDegLng
            val y2 = (p2.latitude - lat) * metersPerDegLat
            val dx = x2 - x1
            val dy = y2 - y1
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq == 0.0) 0.0 else (-(x1 * dx + y1 * dy) / lenSq).coerceIn(0.0, 1.0)
            val projX = x1 + t * dx
            val projY = y1 + t * dy
            val dist = sqrt(projX * projX + projY * projY)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
                bestT = t
            }
        }

        val a = points[bestIdx]
        val b = points[bestIdx + 1]
        val projected = com.example.data.model.LatLngPoint(
            latitude = a.latitude + (b.latitude - a.latitude) * bestT,
            longitude = a.longitude + (b.longitude - a.longitude) * bestT
        )
        return PolylineHit(bestIdx, projected, bestDist)
    }
}
