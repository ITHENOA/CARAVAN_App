package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
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
        private const val KEY_AVATAR_COLOR = "avatar_color"

        private const val KEY_LAST_TRIP_ID = "last_trip_id"
        private const val KEY_LAST_INVITE_CODE = "last_invite_code"
        private const val KEY_LAST_TRIP_NAME = "last_trip_name"
        private const val KEY_LAST_LEADER_TOKEN = "last_leader_token"
        private const val KEY_LAST_IS_LEADER = "last_is_leader"

        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_WS_BASE_URL = "ws_base_url"
        private const val KEY_MOCK_FLEET = "mock_fleet"
        private const val KEY_METRIC_UNITS = "metric_units"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_QUICK_PROMPTS = "quick_prompts"
        private const val KEY_IS_DARK_MODE = "is_dark_mode"
        private const val KEY_NESHAN_API_KEY = "neshan_api_key"

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
        val color = prefs.getString(KEY_AVATAR_COLOR, "#0EA5E9") ?: "#0EA5E9"
        return UserProfile(
            clientId = clientId,
            displayName = name,
            carName = car,
            avatarColor = color
        )
    }

    fun saveUserProfile(profile: UserProfile) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, profile.clientId)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .putString(KEY_CAR_NAME, profile.carName)
            .putString(KEY_AVATAR_COLOR, profile.avatarColor)
            .apply()
    }

    fun getSavedTrip(): SavedTrip? {
        val tripId = prefs.getString(KEY_LAST_TRIP_ID, null) ?: return null
        val inviteCode = prefs.getString(KEY_LAST_INVITE_CODE, "") ?: ""
        val tripName = prefs.getString(KEY_LAST_TRIP_NAME, "Caravan Trip") ?: "Caravan Trip"
        val leaderToken = prefs.getString(KEY_LAST_LEADER_TOKEN, null)
        val isLeader = prefs.getBoolean(KEY_LAST_IS_LEADER, false)
        return SavedTrip(
            tripId = tripId,
            inviteCode = inviteCode,
            tripName = tripName,
            leaderToken = leaderToken,
            isLeader = isLeader
        )
    }

    fun saveTrip(trip: SavedTrip) {
        prefs.edit()
            .putString(KEY_LAST_TRIP_ID, trip.tripId)
            .putString(KEY_LAST_INVITE_CODE, trip.inviteCode)
            .putString(KEY_LAST_TRIP_NAME, trip.tripName)
            .putString(KEY_LAST_LEADER_TOKEN, trip.leaderToken)
            .putBoolean(KEY_LAST_IS_LEADER, trip.isLeader)
            .apply()
    }

    fun clearSavedTrip() {
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
            ?: "https://caravan-backend.ithenoa.workers.dev"
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value.trim()).apply()

    var wsBaseUrl: String
        get() = prefs.getString(KEY_WS_BASE_URL, "wss://caravan-backend.ithenoa.workers.dev")
            ?: "wss://caravan-backend.ithenoa.workers.dev"
        set(value) = prefs.edit().putString(KEY_WS_BASE_URL, value.trim()).apply()

    var isMockFleetEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOCK_FLEET, false)
        set(value) = prefs.edit().putBoolean(KEY_MOCK_FLEET, value).apply()

    var isMetricUnits: Boolean
        get() = prefs.getBoolean(KEY_METRIC_UNITS, true)
        set(value) = prefs.edit().putBoolean(KEY_METRIC_UNITS, value).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var isHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_IS_DARK_MODE, false) // Default to clean Light mode so user can see light mode immediately
        set(value) = prefs.edit().putBoolean(KEY_IS_DARK_MODE, value).apply()

    var neshanApiKey: String
        get() = prefs.getString(KEY_NESHAN_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NESHAN_API_KEY, value.trim()).apply()

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
