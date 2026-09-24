package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class CaravanTripForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTripName: String = "Convoy"
    private var currentMemberCount: Int = 1

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Caravan:TripActiveWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L) // 12-hour safeguard limit
                }
                Log.d(TAG, "Acquired WakeLock for screen-off convoy tracking")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Stopping foreground service due to null intent")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping foreground service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE -> {
                val name = intent.getStringExtra(EXTRA_TRIP_NAME) ?: currentTripName
                val count = intent.getIntExtra(EXTRA_MEMBER_COUNT, currentMemberCount)
                currentTripName = name
                currentMemberCount = count

                acquireWakeLock()
                val notification = buildNotification(currentTripName, currentMemberCount)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground with location type failed: ${e.message}, falling back")
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (err: Exception) {
                        Log.e(TAG, "startForeground fallback failed: ${err.message}")
                    }
                }
            }
            else -> {
                Log.d(TAG, "Unknown action ${intent.action}, stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(tripName: String, memberCount: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val leaveIntent = Intent(this, LeaveTripReceiver::class.java).apply {
            action = LeaveTripReceiver.ACTION_LEAVE_CONVOY
        }
        val leavePendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            leaveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val memberText = if (memberCount <= 1) "1 member" else "$memberCount members"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Convoy Active • $tripName")
            .setContentText("$memberText connected • Active in background & screen off")
            .setSubText("Caravan")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Leave Convoy",
                leavePendingIntent
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Active Convoy Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintains continuous connection, live location, and voice in background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CaravanTripService"
        const val CHANNEL_ID = "caravan_active_trip_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START = "com.example.caravan.ACTION_START_TRIP_SERVICE"
        const val ACTION_STOP = "com.example.caravan.ACTION_STOP_TRIP_SERVICE"
        const val ACTION_UPDATE = "com.example.caravan.ACTION_UPDATE_TRIP_SERVICE"

        const val EXTRA_TRIP_NAME = "extra_trip_name"
        const val EXTRA_MEMBER_COUNT = "extra_member_count"

        var onLeaveRequested: (() -> Unit)? = null

        fun requestLeave(context: Context) {
            try {
                onLeaveRequested?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking onLeaveRequested: ${e.message}")
            }
            stop(context)
        }

        fun start(context: Context, tripName: String, memberCount: Int) {
            val intent = Intent(context, CaravanTripForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRIP_NAME, tripName)
                putExtra(EXTRA_MEMBER_COUNT, memberCount)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to startForegroundService: ${e.message}")
            }
        }

        fun update(context: Context, tripName: String, memberCount: Int) {
            val intent = Intent(context, CaravanTripForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TRIP_NAME, tripName)
                putExtra(EXTRA_MEMBER_COUNT, memberCount)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaravanTripForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop service: ${e.message}")
            }
        }
    }
}
