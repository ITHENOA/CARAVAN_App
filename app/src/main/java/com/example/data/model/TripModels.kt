package com.example.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TripDestination(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedById: String = "",
    val updatedByName: String = "",
    val colorHex: String? = null
)

@Serializable
data class MapMark(
    val clientId: String,
    val displayName: String = "Driver",
    val latitude: Double,
    val longitude: Double,
    val color: String = "#0EA5E9",
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class LatLngPoint(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class SharedRoute(
    val clientId: String,
    val colorHex: String = "#2563EB",
    val points: List<LatLngPoint> = emptyList()
)

@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val clientId: String,
    val displayName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val avatarColor: String = "#0EA5E9"
)

@Serializable
data class UserProfile(
    val clientId: String = java.util.UUID.randomUUID().toString(),
    val displayName: String = "Driver",
    val carName: String = "SUV",
    val avatarColor: String = "#0EA5E9"
)

@Serializable
data class SavedTrip(
    val tripId: String,
    val inviteCode: String,
    val tripName: String,
    val leaderToken: String? = null,
    val isLeader: Boolean = false,
    val savedAt: Long = System.currentTimeMillis()
)
