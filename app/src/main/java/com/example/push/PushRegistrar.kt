package com.example.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object PushRegistrar {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CaravanFirebaseMessagingService.CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CaravanFirebaseMessagingService.CHANNEL_ID,
                "Convoy presence",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when a convoy member comes online"
            },
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns FCM token if Firebase is configured (google-services.json present), else null.
     */
    suspend fun fetchToken(context: Context): String? {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context) ?: return null
            }
            ensureChannel(context)
            val token = FirebaseMessaging.getInstance().token.await()
            context.getSharedPreferences(CaravanFirebaseMessagingService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CaravanFirebaseMessagingService.KEY_TOKEN, token)
                .apply()
            token
        } catch (e: Exception) {
            android.util.Log.w("PushRegistrar", "FCM token unavailable (is google-services.json present?)", e)
            null
        }
    }
}
