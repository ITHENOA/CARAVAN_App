package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.MemberKind
import com.example.data.model.SavedTrip
import com.example.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("caravan_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CAR_NAME = "car_name"
        private const val KEY_MEMBER_KIND = "member_kind"
        private const val KEY_AVATAR_COLOR = "avatar_color"

        private const val KEY_LAST_TRIP_ID = "last_trip_id"
        private const val KEY_LAST_INVITE_CODE = "last_invite_code"
        private const val KEY_LAST_TRIP_NAME = "last_trip_name"
        private const val KEY_LAST_LEADER_TOKEN = "last_leader_token"
        private const val KEY_LAST_IS_LEADER = "last_is_leader"
        private const val KEY_SAVED_TRIPS = "saved_trips"

        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_WS_BASE_URL = "ws_base_url"
        private const val KEY_MOCK_FLEET = "mock_fleet"
        private const val KEY_METRIC_UNITS = "metric_units"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_MESSAGE_SOUND_ENABLED = "message_sound_enabled"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_DRIVING_VIEW_ZOOM = "driving_view_zoom"
        private const val KEY_DRIVING_MARKER_POSITION = "driving_marker_position"
        private const val KEY_CONVOY_FRAMING_RADIUS_METERS = "convoy_framing_radius_meters"
        private const val KEY_QUICK_PROMPTS = "quick_prompts"
        private const val KEY_IS_DARK_MODE = "is_dark_mode"
        const val MAP_THEME_AUTO = "auto"
        const val MAP_THEME_DARK = "dark"
        const val MAP_THEME_LIGHT = "light"
        private const val KEY_MAP_THEME = "map_theme_style"
        private const val KEY_NESHAN_API_KEY = "neshan_api_key"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_USE_AETHER = "use_aether_proxy"
        private const val KEY_AETHER_PROTOCOL = "aether_protocol"
        private const val KEY_AETHER_SCAN = "aether_scan"
        private const val KEY_AETHER_NOIZE = "aether_noize"
        private const val KEY_AETHER_IP = "aether_ip"
        private const val KEY_DNS_PRESET = "dns_preset"
        private const val KEY_DNS_ENABLED = "dns_enabled"
        private const val KEY_DNS_CUSTOM_PRIMARY = "dns_custom_primary"
        private const val KEY_DNS_CUSTOM_SECONDARY = "dns_custom_secondary"

        val DEFAULT_QUICK_PROMPTS = listOf(
            "⛽ Need fuel",
            "🚻 Restroom break",
            "⚠️ Road hazard",
            "🛑 Stopping now",
            "🏎️ Catch up",
            "🐢 Slow down",
            "☕ Coffee run",
            "📍 Rendezvous"
        )
    }

    fun getUserProfile(): UserProfile {
        var clientId = prefs.getString(KEY_CLIENT_ID, null)
        if (clientId == null) {
            clientId = UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()
        }
        val name = prefs.getString(KEY_DISPLAY_NAME, "Lead Driver") ?: "Lead Driver"
        val car = prefs.getString(KEY_CAR_NAME, "SUV (Black)") ?: "SUV (Black)"
        val kind = MemberKind.fromWire(prefs.getString(KEY_MEMBER_KIND, "vehicle"))
        val color = prefs.getString(KEY_AVATAR_COLOR, "#0EA5E9") ?: "#0EA5E9"
        return UserProfile(
            clientId = clientId,
            displayName = name,
            carName = car,
            memberKind = kind,
            avatarColor = color.uppercase()
        )
    }

    fun saveUserProfile(profile: UserProfile) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, profile.clientId)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .putString(KEY_CAR_NAME, profile.carName)
            .putString(KEY_MEMBER_KIND, profile.memberKind.wire)
            .putString(KEY_AVATAR_COLOR, profile.avatarColor)
            .apply()
    }

    fun getSavedTrips(): List<SavedTrip> {
        val raw = prefs.getString(KEY_SAVED_TRIPS, null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            SavedTrip(
                                tripId = o.getString("tripId"),
                                inviteCode = o.optString("inviteCode", ""),
                                tripName = o.optString("tripName", "Caravan Trip"),
                                leaderToken = if (o.isNull("leaderToken")) null
                                    else o.optString("leaderToken").takeIf { it.isNotBlank() },
                                isLeader = o.optBoolean("isLeader", false),
                                savedAt = o.optLong("savedAt", System.currentTimeMillis())
                            )
                        )
                    }
                }.sortedByDescending { it.savedAt }
            } catch (_: Exception) {
                emptyList()
            }
        }
        // Migrate legacy single-trip keys once
        val legacy = getLegacySavedTrip() ?: return emptyList()
        saveTrips(listOf(legacy))
        clearLegacyTripKeys()
        return listOf(legacy)
    }

    fun getSavedTrip(): SavedTrip? = getSavedTrips().firstOrNull()

    fun saveTrip(trip: SavedTrip) {
        val next = listOf(trip.copy(savedAt = System.currentTimeMillis())) +
            getSavedTrips().filterNot { it.tripId == trip.tripId }
        saveTrips(next)
    }

    fun removeTrip(tripId: String) {
        saveTrips(getSavedTrips().filterNot { it.tripId == tripId })
    }

    fun clearSavedTrip() {
        prefs.edit().remove(KEY_SAVED_TRIPS).apply()
        clearLegacyTripKeys()
    }

    private fun saveTrips(trips: List<SavedTrip>) {
        val arr = JSONArray()
        trips.forEach { trip ->
            arr.put(
                JSONObject()
                    .put("tripId", trip.tripId)
                    .put("inviteCode", trip.inviteCode)
                    .put("tripName", trip.tripName)
                    .put("leaderToken", trip.leaderToken)
                    .put("isLeader", trip.isLeader)
                    .put("savedAt", trip.savedAt)
            )
        }
        prefs.edit().putString(KEY_SAVED_TRIPS, arr.toString()).apply()
        clearLegacyTripKeys()
    }

    private fun getLegacySavedTrip(): SavedTrip? {
        val tripId = prefs.getString(KEY_LAST_TRIP_ID, null) ?: return null
        return SavedTrip(
            tripId = tripId,
            inviteCode = prefs.getString(KEY_LAST_INVITE_CODE, "") ?: "",
            tripName = prefs.getString(KEY_LAST_TRIP_NAME, "Caravan Trip") ?: "Caravan Trip",
            leaderToken = prefs.getString(KEY_LAST_LEADER_TOKEN, null),
            isLeader = prefs.getBoolean(KEY_LAST_IS_LEADER, false)
        )
    }

    private fun clearLegacyTripKeys() {
        prefs.edit()
            .remove(KEY_LAST_TRIP_ID)
            .remove(KEY_LAST_INVITE_CODE)
            .remove(KEY_LAST_TRIP_NAME)
            .remove(KEY_LAST_LEADER_TOKEN)
            .remove(KEY_LAST_IS_LEADER)
            .apply()
    }

    var apiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, "https://caravan-backend.ithenoa.workers.dev")
            ?.trim()?.trimEnd('/')?.ifBlank { "https://caravan-backend.ithenoa.workers.dev" }
            ?: "https://caravan-backend.ithenoa.workers.dev"
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value.trim().trimEnd('/')).apply()

    var wsBaseUrl: String
        get() = prefs.getString(KEY_WS_BASE_URL, "wss://caravan-backend.ithenoa.workers.dev")
            ?.trim()?.trimEnd('/')?.ifBlank { "wss://caravan-backend.ithenoa.workers.dev" }
            ?: "wss://caravan-backend.ithenoa.workers.dev"
        set(value) = prefs.edit().putString(KEY_WS_BASE_URL, value.trim().trimEnd('/')).apply()

    var isMockFleetEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOCK_FLEET, false)
        set(value) = prefs.edit().putBoolean(KEY_MOCK_FLEET, value).apply()

    var isMetricUnits: Boolean
        get() = prefs.getBoolean(KEY_METRIC_UNITS, true)
        set(value) = prefs.edit().putBoolean(KEY_METRIC_UNITS, value).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var isMessageSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESSAGE_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MESSAGE_SOUND_ENABLED, value).apply()

    var isHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, value).apply()

    var drivingViewZoom: Float
        get() = prefs.getFloat(KEY_DRIVING_VIEW_ZOOM, 16.5f)
        set(value) = prefs.edit().putFloat(KEY_DRIVING_VIEW_ZOOM, value.coerceIn(14.0f, 18.5f)).apply()

    var drivingMarkerPosition: Float
        get() = prefs.getFloat(KEY_DRIVING_MARKER_POSITION, 0.68f)
        set(value) = prefs.edit().putFloat(KEY_DRIVING_MARKER_POSITION, value.coerceIn(0.50f, 0.85f)).apply()

    /** Maximum radius in meters to frame online convoy members. -1 means all convoy (unlimited). */
    var convoyFramingRadiusMeters: Int
        get() = prefs.getInt(KEY_CONVOY_FRAMING_RADIUS_METERS, -1)
        set(value) = prefs.edit().putInt(KEY_CONVOY_FRAMING_RADIUS_METERS, value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_IS_DARK_MODE, false) // Default to clean Light mode so user can see light mode immediately
        set(value) = prefs.edit().putBoolean(KEY_IS_DARK_MODE, value).apply()

    var mapTheme: String
        get() = prefs.getString(KEY_MAP_THEME, MAP_THEME_AUTO) ?: MAP_THEME_AUTO
        set(value) = prefs.edit().putString(KEY_MAP_THEME, value).apply()

    var neshanApiKey: String
        get() = prefs.getString(KEY_NESHAN_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NESHAN_API_KEY, value.trim()).apply()

    /** When false, OkHttp uses the device/system proxy. When true, [proxyHost]/[proxyPort]. */
    var isProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ENABLED, value).apply()

    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROXY_HOST, value.trim()).apply()

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PROXY_PORT, value.coerceIn(1, 65535)).apply()

    /** "http" or "socks" for manual proxy. */
    var proxyType: String
        get() = prefs.getString(KEY_PROXY_TYPE, "http") ?: "http"
        set(value) = prefs.edit().putString(KEY_PROXY_TYPE, value).apply()

    /** When true, traffic goes through Aether SOCKS at 127.0.0.1:1819 (overrides manual proxy). */
    var useAetherProxy: Boolean
        get() = prefs.getBoolean(KEY_USE_AETHER, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_AETHER, value).apply()

    var aetherProtocol: String
        get() = prefs.getString(KEY_AETHER_PROTOCOL, "masque") ?: "masque"
        set(value) = prefs.edit().putString(KEY_AETHER_PROTOCOL, value).apply()

    var aetherScan: String
        get() = prefs.getString(KEY_AETHER_SCAN, "balanced") ?: "balanced"
        set(value) = prefs.edit().putString(KEY_AETHER_SCAN, value).apply()

    var aetherNoize: String
        get() = prefs.getString(KEY_AETHER_NOIZE, "firewall") ?: "firewall"
        set(value) = prefs.edit().putString(KEY_AETHER_NOIZE, value).apply()

    var aetherIpMode: String
        get() = prefs.getString(KEY_AETHER_IP, "4") ?: "4"
        set(value) = prefs.edit().putString(KEY_AETHER_IP, value).apply()

    /** See [com.example.data.network.DnsPresets] ids: system, cloudflare, google, …, custom */
    var isDnsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DNS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DNS_ENABLED, value).apply()

    var dnsPreset: String
        get() = prefs.getString(KEY_DNS_PRESET, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_DNS_PRESET, value).apply()

    var dnsCustomPrimary: String
        get() = prefs.getString(KEY_DNS_CUSTOM_PRIMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DNS_CUSTOM_PRIMARY, value.trim()).apply()

    var dnsCustomSecondary: String
        get() = prefs.getString(KEY_DNS_CUSTOM_SECONDARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DNS_CUSTOM_SECONDARY, value.trim()).apply()

    fun getQuickPrompts(): List<String> {
        val raw = prefs.getString(KEY_QUICK_PROMPTS, null) ?: return DEFAULT_QUICK_PROMPTS
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            if (list.isEmpty()) DEFAULT_QUICK_PROMPTS else list
        } catch (e: Exception) {
            DEFAULT_QUICK_PROMPTS
        }
    }

    fun saveQuickPrompts(prompts: List<String>) {
        val arr = JSONArray()
        prompts.forEach { arr.put(it) }
        prefs.edit().putString(KEY_QUICK_PROMPTS, arr.toString()).apply()
    }
}
