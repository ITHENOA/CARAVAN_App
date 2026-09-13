package com.example.data.model

import kotlinx.serialization.Serializable

enum class MemberConnectionStatus {
    CONNECTED,
    RECONNECTING,
    OFFLINE
}

@Serializable
data class TripMember(
    val id: String,
    val displayName: String,
    val carName: String? = null,
    val avatarColor: String? = "#0EA5E9",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val heading: Double? = null,
    val lastLocationAt: Long? = null,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val connectionStatus: MemberConnectionStatus = MemberConnectionStatus.CONNECTED,
    val isLeader: Boolean = false
) {
    val isStale: Boolean
        get() {
            val locTime = lastLocationAt ?: return true
            return System.currentTimeMillis() - locTime > 15_000
        }
}
