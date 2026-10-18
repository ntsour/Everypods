package io.automated.ventures.everypods.relaydev

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.relay.ClockSync
import io.automated.ventures.everypods.relay.ControlCodec
import io.automated.ventures.everypods.relay.ControlFrameDecoder
import io.automated.ventures.everypods.relay.ControlMessage
import io.automated.ventures.everypods.relay.DevPsk
import io.automated.ventures.everypods.relay.DriftCorrector
import io.automated.ventures.everypods.relay.JitterBuffer
import io.automated.ventures.everypods.relay.LinearResampler
import io.automated.ventures.everypods.relay.OpenResult
import io.automated.ventures.everypods.relay.OpusDecoder
import io.automated.ventures.everypods.relay.OutputMatcher
import io.automated.ventures.everypods.relay.RelayAead
import io.automated.ventures.everypods.relay.RelayAudioFormat
import io.automated.ventures.everypods.relay.RelayRole
import io.automated.ventures.everypods.relay.X25519
import io.automated.ventures.everypods.utils.RelaySessionState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Maps platform devices to the pure matcher's candidates. */
internal fun AudioDeviceInfo.toCandidate() = OutputMatcher.Candidate(
    id, address ?: "", productName?.toString() ?: "",
    when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputMatcher.Kind.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputMatcher.Kind.BLE_AUDIO
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputMatcher.Kind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputMatcher.Kind.WIRED
        else -> OutputMatcher.Kind.OTHER
    }
)

internal fun outputCandidates(context: Context): List<Pair<AudioDeviceInfo, OutputMatcher.Candidate>> =
    context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it to it.toCandidate() }

/**
 * DEV ONLY (P2) receiver role (Pixel): control TCP + media UDP, AEAD open, adaptive jitter buffer,
 * Opus decode/PLC/FEC, drift-corrected AudioTrack pinned to the matched AirPods. Never plays to
 * the speaker: route mismatch pauses + flushes. Our own announcements duck it; only a permanent
 * focus loss ends the session.
 */
class RelayReceiverService : Service() {
    companion object {
        const val TAG = "RelayReceiver"
        const val ROLE = "receiver"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "relaydev_receiver"
        private const val DUCK_VOLUME = 0.2f
    }

    @Volatile private var closing = false
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var client: Socket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var am: AudioManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { shutdown("user stop"); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        if (RelaySessionState.active) { Log.w(TAG, "relay already active (${RelaySessionState.activeRole})"); return START_NOT_STICKY }
        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        am = getSystemService(AudioManager::class.java)
        try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(RelayDevPrefs.TCP_PORT)) }
            udp = DatagramSocket(null).apply { reuseAddress = true; receiveBufferSize = 256 * 1024; bind(InetSocketAddress(RelayDevPrefs.UDP_PORT)) }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EveryPodsRelay:receiver").apply { acquire() }
            wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "EveryPodsRelay:receiver").apply { acquire() }
            RelaySessionState.begin(ROLE)
            Log.i(TAG, "listening tcp=${RelayDevPrefs.TCP_PORT} udp=${RelayDevPrefs.UDP_PORT} ips=${localIpv4()}")
            status("Waiting for source on ${localIpv4().joinToString()}")
            thread(name = "RelayReceiverAccept") { acceptLoop() }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e); status("Error: ${e.message}"); shutdown("start failed")
        }
        return START_NOT_STICKY
    }

    private fun acceptLoop() {
        while (!closing) {
            val s = try { server?.accept() } catch (_: Exception) { null } ?: break
            if (!s.inetAddress.isSiteLocalAddress) { Log.w(TAG, "rejecting non-private source ${s.inetAddress}"); s.close(); continue }
            client = s
            try { Session(s).run() } catch (e: Exception) { if (!closing) Log.w(TAG, "session ended: ${e.message}") }
            finally { try { s.close() } catch (_: Exception) {}; client = null }
            if (!closing) status("Waiting for source on ${localIpv4().joinToString()}")
        }
    }

    private inner class Session(private val sock: Socket) {
        @Volatile var ended = false
        @Volatile var paused = false
        @Volatile var ducked = false
        lateinit var track: AudioTrack
        lateinit var chosen: OutputMatcher.Candidate
        lateinit var focus: AudioFocusRequest
        val jb = JitterBuffer()
        val drift = DriftCorrector()
        val resampler = LinearResampler()
        val clock = ClockSync()
        var sessionStart = 0L
        @Volatile var latencyMs = -1.0
        @Volatile var authFailures = 0L
        @Volatile var lastPacketMs = SystemClock.elapsedRealtime()
        val outLock = Any()

        fun send(m: ControlMessage) = synchronized(outLock) { sock.getOutputStream().apply { write(ControlCodec.frame(m)); flush() } }

        fun run() {
            sock.tcpNoDelay = true
            sock.soTimeout = 5000
            val input = sock.getInputStream()
            val dec = ControlFrameDecoder()
            val buf = ByteArray(2048)
            val pending = ArrayDeque<ControlMessage>()
            fun next(): ControlMessage {
                while (pending.isEmpty()) {
                    val n = input.read(buf); if (n < 0) throw java.io.IOException("source closed")
                    pending.addAll(dec.feed(buf, 0, n))
                }
                return pending.removeFirst()
            }
            val hello = next() as? ControlMessage.Hello ?: throw java.io.IOException("expected Hello")
            // Pick the output before accepting: refuse instead of any speaker fallback.
            val match = OutputMatcher.choose(outputCandidates(this@RelayReceiverService).map { it.second }, null, RelayDevPrefs.outputAddress(this@RelayReceiverService))
            if (match is OutputMatcher.Result.Refused) {
                Log.w(TAG, "refusing session from ${hello.deviceId}: ${match.reason}")
                status("Refused: ${match.reason}")
                send(ControlMessage.Stop(ControlMessage.STOP_ROUTE_LOST)); return
            }
            chosen = (match as OutputMatcher.Result.Match).candidate
            Log.i(TAG, "output matched: ${chosen.name} ${chosen.address} (${match.reason})")
            val eph = X25519.generate(); val myPub = X25519.rawPublic(eph)
            send(ControlMessage.Hello(1, Build.MODEL.take(64), myPub))
            val keys = DevPsk.sessionKeys(X25519.agree(eph.private, hello.ephemeralPublic),
                DevPsk.fromPassphrase(RelayDevPrefs.testKey(this@RelayReceiverService)), hello.ephemeralPublic, myPub)
            val aead = RelayAead(keys, RelayRole.RECEIVER)
            val start = next() as? ControlMessage.Start ?: throw java.io.IOException("expected Start")
            require(start.sampleRate == RelayAudioFormat.SAMPLE_RATE && start.channels == RelayAudioFormat.CHANNELS && start.frameMs == RelayAudioFormat.FRAME_MS) { "unsupported stream $start" }
            sessionStart = start.sessionStartMicros
            sock.soTimeout = 0
            if (!openOutput()) { send(ControlMessage.Stop(ControlMessage.STOP_ROUTE_LOST)); return }
            Log.i(TAG, "session started from ${hello.deviceId} ${sock.inetAddress.hostAddress}")
            status("Streaming from ${hello.deviceId} to ${chosen.name}")
            val deviceCb = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    if (removed.any { it.address.equals(chosen.address, true) }) {
                        Log.w(TAG, "matched AirPods output removed mid-session (A2DP left this phone, e.g. moved to the source device); pausing, no speaker fallback")
                        paused = true; flushOutput(); status("AirPods left this phone: paused")
                    }
                }
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    val back = added.firstOrNull { it.address.equals(chosen.address, true) && it.toCandidate().isBluetoothOutput } ?: return
                    track.setPreferredDevice(back)
                    Log.i(TAG, "matched AirPods output back; resuming after route check")
                    main.postDelayed({ if (OutputMatcher.routeOk(track.routedDevice?.toCandidate(), chosen)) { paused = false; status("Streaming to ${chosen.name}") } }, 500)
                }
            }
            am.registerAudioDeviceCallback(deviceCb, main)
            val udpThread = thread(name = "RelayReceiverUdp") { udpLoop(aead) }
            val playThread = thread(name = "RelayReceiverPlay", priority = Thread.MAX_PRIORITY) { playLoop() }
            val metricsThread = thread(name = "RelayReceiverMetrics") { metricsLoop() }
            try {
                while (!ended && !closing) {
                    val n = input.read(buf); if (n < 0) break
                    for (m in dec.feed(buf, 0, n)) when (m) {
                        is ControlMessage.Keepalive -> if (m.echoOfMicros != 0L) clock.add(m.echoOfMicros, m.sentAtMicros, nowMicros())
                        is ControlMessage.Stop -> { Log.i(TAG, "source stopped (reason ${m.reason})"); ended = true }
                        else -> Unit
                    }
                }
            } finally {
                ended = true
                am.unregisterAudioDeviceCallback(deviceCb)
                udpThread.interrupt(); playThread.join(1000); metricsThread.interrupt()
                closeOutput()
                logMetrics("end")
            }
        }

        private fun openOutput(): Boolean {
            val device = outputCandidates(this@RelayReceiverService).firstOrNull { it.second.id == chosen.id }?.first ?: return false
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true) // we duck ourselves, so the level stays under our control
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            ducked = true; if (::track.isInitialized) track.setVolume(DUCK_VOLUME)
                            Log.i(TAG, "focus transient loss ($change): ducking relay, keep streaming")
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            ducked = false; if (::track.isInitialized) track.setVolume(1f)
                            Log.i(TAG, "focus regained: relay volume restored")
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.i(TAG, "permanent focus loss: stopping relay")
                            thread { try { send(ControlMessage.Stop(ControlMessage.STOP_FOCUS_LOST)) } catch (_: Exception) {}; ended = true; try { sock.close() } catch (_: Exception) {} }
                        }
                    }
                }, main).build()
            if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { Log.w(TAG, "audio focus denied"); return false }
            val fmt = AudioFormat.Builder().setSampleRate(RelayAudioFormat.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val min = AudioTrack.getMinBufferSize(RelayAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min * 2, 3840 * 3)).build()
            if (!track.setPreferredDevice(device)) { Log.w(TAG, "setPreferredDevice rejected"); return false }
            track.addOnRoutingChangedListener({ r ->
                if (!OutputMatcher.routeOk(r.routedDevice?.toCandidate(), chosen) && !paused) {
                    Log.w(TAG, "route left matched AirPods (now ${r.routedDevice?.productName}); pausing, no speaker fallback")
                    paused = true; flushOutput()
                }
            }, main)
            track.play()
            track.write(ShortArray(960), 0, 960, AudioTrack.WRITE_NON_BLOCKING) // silence primes routing
            val deadline = SystemClock.elapsedRealtime() + 1000
            while (SystemClock.elapsedRealtime() < deadline && !OutputMatcher.routeOk(track.routedDevice?.toCandidate(), chosen)) Thread.sleep(10)
            if (!OutputMatcher.routeOk(track.routedDevice?.toCandidate(), chosen)) {
                Log.w(TAG, "route check failed (routed=${track.routedDevice?.productName}); no audio written"); closeOutput(); return false
            }
            Log.i(TAG, "route verified: ${track.routedDevice?.productName} ${track.routedDevice?.address}")
            return true
        }

        private fun flushOutput() {
            synchronized(jb) { jb.flush() }
            drift.reset(); resampler.reset()
            if (::track.isInitialized) try { track.pause(); track.flush(); track.play() } catch (_: Exception) {}
        }

        private fun closeOutput() {
            synchronized(jb) { jb.flush() }
            if (::track.isInitialized) try { track.pause(); track.flush(); track.release() } catch (_: Exception) {}
            if (::focus.isInitialized) am.abandonAudioFocusRequest(focus)
        }

        private fun udpLoop(aead: RelayAead) {
            val u = udp ?: return
            val pkt = DatagramPacket(ByteArray(2048), 2048)
            while (!ended && !closing) {
                try { u.soTimeout = 500; u.receive(pkt) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break }
                if (pkt.address != sock.inetAddress) continue
                when (val r = aead.open(pkt.data.copyOf(pkt.length))) {
                    is OpenResult.Ok -> {
                        lastPacketMs = SystemClock.elapsedRealtime()
                        if (!paused) synchronized(jb) { jb.offer(r.packet.seq, r.packet.timestamp, r.packet.payload, SystemClock.elapsedRealtimeNanos() / 1e6) }
                    }
                    is OpenResult.Rejected -> {
                        authFailures++
                        if (authFailures == 50L) {
                            Log.w(TAG, "50 media packets rejected (${r.reason}); wrong test key? stopping")
                            try { send(ControlMessage.Stop(ControlMessage.STOP_ERROR)) } catch (_: Exception) {}
                            ended = true; try { sock.close() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        private fun playLoop() {
            val decoder = OpusDecoder()
            val silence = ShortArray(RelayAudioFormat.FRAME_SAMPLES * RelayAudioFormat.CHANNELS)
            val trackLatencyMs = track.bufferSizeInFrames * 1000.0 / RelayAudioFormat.SAMPLE_RATE
            try {
                while (!ended && !closing) {
                    if (paused) { synchronized(jb) { jb.flush() }; Thread.sleep(20); continue }
                    val pull: JitterBuffer.Pull
                    val ratio: Double
                    synchronized(jb) { pull = jb.pull(); ratio = drift.update(jb.depthMs().toDouble(), jb.targetMs) }
                    val pcm = when (pull) {
                        is JitterBuffer.Pull.Frame -> {
                            val off = clock.offsetMicros
                            if (off != null && sessionStart != 0L) {
                                val capturedSenderUs = sessionStart + pull.timestamp * 1_000_000L / RelayAudioFormat.SAMPLE_RATE
                                val l = (nowMicros() - (capturedSenderUs - off)) / 1000.0 + trackLatencyMs
                                latencyMs = if (latencyMs < 0) l else latencyMs + 0.02 * (l - latencyMs)
                            }
                            decoder.decode(pull.payload)
                        }
                        is JitterBuffer.Pull.Lost -> if (pull.fecFrom != null) decoder.decode(pull.fecFrom, fec = true) else decoder.decode(null)
                        JitterBuffer.Pull.Buffering -> silence
                    }
                    val out = if (pull === JitterBuffer.Pull.Buffering) pcm else resampler.process(pcm, ratio)
                    if (paused || ended) continue
                    val w = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { Log.w(TAG, "AudioTrack write $w"); break }
                }
            } catch (_: InterruptedException) {
            } finally { decoder.close() }
        }

        private fun metricsLoop() {
            var tick = 0
            try {
                while (!ended && !closing) {
                    Thread.sleep(1000); tick++
                    try { send(ControlMessage.Keepalive(nowMicros())) } catch (_: Exception) {}
                    val silentFor = SystemClock.elapsedRealtime() - lastPacketMs
                    if (silentFor > 3000 && !paused) status("Network loss: no audio packets for ${silentFor / 1000}s")
                    if (silentFor > 30_000) { Log.w(TAG, "no packets for 30 s: ending session (network loss)"); ended = true; try { sock.close() } catch (_: Exception) {} }
                    if (tick % 5 == 0) logMetrics("tick")
                }
            } catch (_: InterruptedException) {}
        }

        fun logMetrics(event: String) {
            val (line, stats) = synchronized(jb) {
                "RelayMetrics $event latencyMs=${latencyMs.toInt()} targetMs=${jb.targetMs.toInt()} depthMs=${jb.depthMs()} " +
                    "jitterMs=${"%.1f".format(jb.jitterMs)} received=${jb.received} lost=${jb.lost} late=${jb.late} dup=${jb.duplicates} " +
                    "underruns=${jb.underruns} trimmed=${jb.trimmed} driftPpm=${((drift.ratio - 1) * 1e6).toInt()} " +
                    "rttMs=${(clock.rttMicros ?: -1000) / 1000} authFail=$authFailures ducked=$ducked paused=$paused" to
                    ControlMessage.Stats(jb.received, jb.lost, jb.late, jb.underruns, jb.depthMs(), latencyMs.toInt())
            }
            Log.i(TAG, line)
            if (event == "tick") {
                try { send(stats) } catch (_: Exception) {}
                if (!paused) status("Streaming to ${chosen.name} · latency ${latencyMs.toInt()} ms · lost ${stats.lost} · underruns ${stats.underruns}")
            }
        }
    }

    private fun shutdown(reason: String) {
        if (closing) return
        closing = true
        Log.i(TAG, "stopping receiver: $reason")
        client?.let { c -> thread { try { c.getOutputStream().write(ControlCodec.frame(ControlMessage.Stop(ControlMessage.STOP_USER))) } catch (_: Exception) {}; try { c.close() } catch (_: Exception) {} } }
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        RelaySessionState.end(ROLE)
        status("Receiver stopped: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { shutdown("service destroyed"); super.onDestroy() }

    private fun status(s: String) { RelayDevStatus.receiver.value = s }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Relay receiver (dev)", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 20, Intent(this, RelayReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 21, Intent(this, RelayDevActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Wi-Fi relay receiver (dev)").setContentText("Plays relayed audio on your AirPods only")
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
}
