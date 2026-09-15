package com.example.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Shows FCM presence alerts when the app process is backgrounded / killed.
 * Foreground WebSocket clients already receive [member_joined] and skip server push.
 */
class CaravanFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // ViewModel re-registers on next trip join / reconnect.
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val tripName = message.data["tripName"]?.takeIf { it.isNotBlank() }
        val title = message.notification?.title
            ?: message.data["title"]
            ?: tripName
            ?: "Convoy"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: run {
                val who = message.data["displayName"]?.takeIf { it.isNotBlank() } ?: "Someone"
                val group = tripName ?: "Convoy"
                "$who joined convoy “$group”"
            }

        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_TRIP_ID, message.data["tripId"])
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Convoy presence",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when a convoy member comes online"
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "caravan_presence"
        const val PREFS = "caravan_push"
        const val KEY_TOKEN = "fcm_token"
        const val EXTRA_TRIP_ID = "tripId"
    }
}
