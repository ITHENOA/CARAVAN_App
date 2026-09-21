package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class LeaveTripReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_LEAVE_CONVOY) {
            Log.d("LeaveTripReceiver", "Received ACTION_LEAVE_CONVOY from notification")
            CaravanTripForegroundService.requestLeave(context)
        }
    }

    companion object {
        const val ACTION_LEAVE_CONVOY = "com.example.caravan.ACTION_LEAVE_CONVOY"
    }
}
