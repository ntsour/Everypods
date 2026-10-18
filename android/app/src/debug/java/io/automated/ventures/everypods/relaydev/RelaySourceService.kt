package io.automated.ventures.everypods.relaydev

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Build
import android.util.Log
import io.automated.ventures.everypods.relay.ControlCodec
import io.automated.ventures.everypods.relay.ControlFrameDecoder
import io.automated.ventures.everypods.relay.ControlMessage
import io.automated.ventures.everypods.relay.DevPsk
import io.automated.ventures.everypods.relay.OpusEncoder
import io.automated.ventures.everypods.relay.PcmFramer
import io.automated.ventures.everypods.relay.RelayAead
import io.automated.ventures.everypods.relay.RelayAudioFormat
import io.automated.ventures.everypods.relay.RelayPacket
import io.automated.ventures.everypods.relay.RelayRole
import io.automated.ventures.everypods.relay.X25519
import io.automated.ventures.everypods.utils.RelaySessionState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * DEV ONLY (P2) source role (Xiaomi): AudioPlaybackCapture of all eligible media apps →
 * Opus → AEAD → UDP to the receiver. mediaProjection foreground service; fresh consent per start.
 * Mutes the local media stream during relay and restores it on stop (only if still muted).
 */
class RelaySourceService : Service() {
    companion object {
        const val TAG = "RelaySource"
        const val ROLE = "source"
        const val EXTRA_RESULT = "result"
        const val EXTRA_TOKEN = "token"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "relaydev_source"
    }

    @Volatile private var closing = false
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var control: Socket? = null
    private var udp: DatagramSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var savedVolume = -1
    private var battery: BatteryMeter? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var sent = 0L
    @Volatile private var sendErrors = 0L
    @Volatile private var silentFrames = 0L
    @Volatile private var lastStats = "no stats yet"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { finish("user stop", ControlMessage.STOP_USER); return START_NOT_STICKY }
        if (RelaySessionState.active) { Log.w(TAG, "relay already active (${RelaySessionState.activeRole}); ignoring start"); return START_NOT_STICKY }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("RECORD_AUDIO not granted")
            val token = intent.getParcelableExtra(EXTRA_TOKEN, Intent::class.java) ?: throw IllegalStateException("missing capture consent")
            val host = RelayDevPrefs.host(this)
            val address = requirePrivateIpv4(host)
            val psk = DevPsk.fromPassphrase(RelayDevPrefs.testKey(this))
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_RESULT, 0), token)
                ?: throw IllegalStateException("no MediaProjection")
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!closing) main.post { finish("capture revoked by system", ControlMessage.STOP_CAPTURE_REVOKED) } }
            }, main)
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build() // all eligible media apps (PM default); per-app filter is P4 Advanced
            val fmt = AudioFormat.Builder().setSampleRate(RelayAudioFormat.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(RelayAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * 2, 3840 * 4)).build()
            if (recorder!!.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")
            RelaySessionState.begin(ROLE)
            battery = BatteryMeter(this, TAG).also { it.log("start") }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EveryPodsRelay:source").apply { acquire() }
            wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "EveryPodsRelay:source").apply { acquire() }
            status("Connecting to $host…")
            thread(name = "RelaySourceNet") { runSession(address, psk) }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            status("Error: ${e.message}")
            finish("start failed: ${e.message}", ControlMessage.STOP_ERROR)
        }
        return START_NOT_STICKY
    }

    private fun runSession(address: java.net.InetAddress, psk: ByteArray) {
        try {
            val net = wifiNetworkFor(this, address) ?: throw IllegalStateException("join the receiver's Wi-Fi or Pixel hotspot first")
            val sock = Socket().also { control = it }
            net.bindSocket(sock)
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(address, RelayDevPrefs.TCP_PORT), 5000)
            sock.soTimeout = 0
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            val eph = X25519.generate()
            val myPub = X25519.rawPublic(eph)
            out.write(ControlCodec.frame(ControlMessage.Hello(1, Build.MODEL.take(64), myPub))); out.flush()
            val dec = ControlFrameDecoder()
            val buf = ByteArray(2048)
            var peer: ControlMessage.Hello? = null
            sock.soTimeout = 5000
            while (peer == null) {
                val n = input.read(buf); if (n < 0) throw java.io.IOException("receiver closed during handshake")
                dec.feed(buf, 0, n).forEach { m ->
                    when (m) {
                        is ControlMessage.Hello -> peer = m
                        is ControlMessage.Stop -> throw java.io.IOException("receiver refused (reason ${m.reason}); check its AirPods output")
                        else -> Unit
                    }
                }
            }
            sock.soTimeout = 0
            val keys = DevPsk.sessionKeys(X25519.agree(eph.private, peer!!.ephemeralPublic), psk, myPub, peer!!.ephemeralPublic)
            val aead = RelayAead(keys, RelayRole.SOURCE)
            val u = DatagramSocket().also { udp = it }
            net.bindSocket(u)
            u.connect(InetSocketAddress(address, RelayDevPrefs.UDP_PORT))
            val sessionStart = nowMicros()
            val outLock = Any()
            fun send(m: ControlMessage) = synchronized(outLock) { out.write(ControlCodec.frame(m)); out.flush() }
            send(ControlMessage.Start(RelayPacket.CODEC_OPUS, RelayAudioFormat.SAMPLE_RATE, RelayAudioFormat.CHANNELS, RelayAudioFormat.FRAME_MS, RelayAudioFormat.BITRATE, RelayDevPrefs.UDP_PORT, sessionStart))
            Log.i(TAG, "session started with ${peer!!.deviceId} at $address (control TCP ${RelayDevPrefs.TCP_PORT}, media UDP ${RelayDevPrefs.UDP_PORT})")
            if (RelayDevPrefs.muteSource(this)) main.post { muteLocal() }
            thread(name = "RelaySourceCapture") { captureLoop(aead, u) }
            thread(name = "RelaySourceBattery") {
                while (!closing) { Thread.sleep(60_000); if (!closing) battery?.log("tick") }
            }
            while (!closing) {
                val n = input.read(buf); if (n < 0) throw java.io.IOException("receiver closed control channel")
                for (m in dec.feed(buf, 0, n)) when (m) {
                    is ControlMessage.Keepalive -> send(ControlMessage.Keepalive(nowMicros(), m.sentAtMicros))
                    is ControlMessage.Stats -> {
                        lastStats = "rx=${m.received} lost=${m.lost} late=${m.late} underruns=${m.underruns} buffer=${m.jitterBufferMs}ms latency=${m.latencyMs}ms"
                        Log.i(TAG, "receiver stats $lastStats sent=$sent sendErrors=$sendErrors silentFrames=$silentFrames")
                        status("Streaming · $lastStats")
                    }
                    is ControlMessage.Stop -> { main.post { finish("receiver stopped (reason ${m.reason})", null) }; return }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!closing) { Log.w(TAG, "session ended: ${e.message}"); status("Network error: ${e.message}"); main.post { finish("network: ${e.message}", null) } }
        }
    }

    private fun captureLoop(aead: RelayAead, u: DatagramSocket) {
        val rec = recorder ?: return
        val enc = OpusEncoder()
        val framer = PcmFramer()
        var seq = 0L
        val read = ShortArray(RelayAudioFormat.FRAME_SAMPLES * RelayAudioFormat.CHANNELS)
        try {
            rec.startRecording()
            while (!closing) {
                val n = rec.read(read, 0, read.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IllegalStateException("AudioRecord read $n")
                framer.push(read, n) { frame ->
                    var sum = 0.0
                    for (s in frame) sum += s.toDouble() * s
                    val silent = sqrt(sum / frame.size) < 3.0 // ≈ -80 dBFS
                    if (silent) silentFrames++
                    val payload = enc.encode(frame)
                    val pkt = RelayPacket(flags = if (silent) RelayPacket.FLAG_SILENCE else 0, seq = seq,
                        timestamp = seq * RelayAudioFormat.FRAME_SAMPLES, payload = payload)
                    seq++
                    val d = aead.seal(pkt)
                    try { u.send(DatagramPacket(d, d.size)); sent++ } catch (e: java.io.IOException) { sendErrors++ }
                }
            }
        } catch (e: Exception) {
            if (!closing) { Log.w(TAG, "capture ended: ${e.message}"); main.post { finish("capture: ${e.message}", ControlMessage.STOP_ERROR) } }
        } finally {
            enc.close()
        }
    }

    private fun muteLocal() {
        val am = getSystemService(AudioManager::class.java)
        if (savedVolume < 0) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            Log.i(TAG, "local media muted (saved volume $savedVolume)")
        }
    }

    private fun restoreLocal() {
        if (savedVolume < 0) return
        val am = getSystemService(AudioManager::class.java)
        // Respect a manual change made during relay: restore only if still muted.
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        Log.i(TAG, "local media volume restored to ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} (saved $savedVolume)")
        savedVolume = -1
    }

    private fun finish(reason: String, notifyPeer: Int?) {
        if (closing) return
        closing = true
        Log.i(TAG, "stopping: $reason sent=$sent sendErrors=$sendErrors silentFrames=$silentFrames last=$lastStats")
        battery?.log("stop")
        val c = control
        if (notifyPeer != null && c != null) thread { try { c.getOutputStream().write(ControlCodec.frame(ControlMessage.Stop(notifyPeer))) } catch (_: Exception) {} ; try { c.close() } catch (_: Exception) {} }
        else try { c?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        recorder = null
        projection?.stop(); projection = null
        try { restoreLocal() } catch (e: Exception) { Log.w(TAG, "restore volume failed: ${e.message}") }
        wake?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        RelaySessionState.end(ROLE)
        status("Source stopped: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { finish("service destroyed", ControlMessage.STOP_USER); super.onDestroy() }

    private fun status(s: String) { RelayDevStatus.source.value = s }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Relay source (dev)", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 10, Intent(this, RelaySourceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 11, Intent(this, RelayDevActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Playing through Pixel (dev)").setContentText("Capturing app audio only; the screen is not recorded")
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
}
