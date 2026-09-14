package com.example.data.model

import kotlinx.serialization.Serializable

enum class MemberConnectionStatus {
    CONNECTED,
    RECONNECTING,
    OFFLINE
}

@Serializable
enum class MemberKind {
    VEHICLE,
    PERSON;

    val wire: String
        get() = when (this) {
            VEHICLE -> "vehicle"
            PERSON -> "person"
        }

    companion object {
        fun fromWire(value: String?): MemberKind =
            if (value == "person") PERSON else VEHICLE
    }
}

@Serializable
data class TripMember(
    val id: String,
    val displayName: String,
    val carName: String? = null,
    val memberKind: MemberKind = MemberKind.VEHICLE,
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
    val isPerson: Boolean get() = memberKind == MemberKind.PERSON

    val isStale: Boolean
        get() {
            val locTime = lastLocationAt ?: return true
            return System.currentTimeMillis() - locTime > 15_000
        }
}
