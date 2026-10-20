/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.opus.NativeOpusCodec
import io.automated.ventures.everypods.relay.transport.RelayJitterBuffer
import io.automated.ventures.everypods.relay.transport.RelayMediaPacket
import io.automated.ventures.everypods.relay.transport.RelayOpusFramer
import io.automated.ventures.everypods.utils.MediaController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Receiver-side Opus playback. AudioTrack is never allowed to fall back to the phone speaker. */
class RelayAudioPlayback(
    context: Context,
    private val expectedAirPodsAddress: String?,
    private val tuning: RelayAudioTuning = RelayAudioTuning.DEFAULT,
    private val onFirstAudioFrame: () -> Unit,
    private val onBuffering: (Boolean) -> Unit,
    private val onInterruption: (RelayInterruption, resumeUnavailable: Boolean) -> Unit,
    private val sourceClockOffsetNanos: () -> Long?,
    private val onDelayMeasurement: (estimateMillis: Int, jitterTargetMillis: Int, outputQueueMillis: Int) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val closed = AtomicBoolean(false)
    private val resourceLock = Any()
    private val jitterBuffer = RelayJitterBuffer(tuning.initialJitterMs, tuning.maximumJitterMs, tuning.frameDurationMs, tuning.adaptiveBufferMode)
    private val codec = NativeOpusCodec(tuning.frameDurationMs, tuning.bitrateKbps, tuning.lowDelayMode)
    private val framer = RelayOpusFramer(codec)
    private val framePcmSamples = NativeOpusCodec.SAMPLE_RATE * tuning.frameDurationMs / 1_000 * NativeOpusCodec.CHANNELS
    private val framePcmBytes = framePcmSamples * 2
    private val handlerThread = HandlerThread("EveryPodsRelayAudioRoute").apply { start() }
    private val handler = Handler(handlerThread.looper)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusHeld = false
    @Volatile private var hasPlayedRealFrame = false
    @Volatile private var receivedFrames = 0L
    @Volatile private var writtenFrames = 0L
    @Volatile private var writtenAudioFrames = 0L
    @Volatile private var lastArrivalMillis: Long? = null
    private var buffering = false
    private var healthySinceMillis: Long? = null
    private var noOutputSinceMillis: Long? = null
    private var worker: Thread? = null
    @Volatile private var interruption = RelayInterruption.NONE
    private var playbackEpoch = 0L
    private var ducked = false
    private var newestSourceTimestampNanos = 0L
    private var discardThroughSourceTimestampNanos = 0L
    private var resumeAtReceiverNanos = 0L
    val isSuspended: Boolean get() = interruption != RelayInterruption.NONE
    val hasAudibleAudio: Boolean get() = !closed.get() && interruption == RelayInterruption.NONE && hasPlayedRealFrame

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (expectedAirPodsAddress?.let(::findBluetoothOutput) == null) fail("AirPods audio disconnected")
        }
    }

    fun start() {
        check(!closed.get())
        check(track == null) { "Bluetooth playback is already active" }
        val expectedAddress = expectedAirPodsAddress?.takeIf(String::isNotBlank)
            ?: error("AirPods connection identity is unavailable")
        val bluetooth = findBluetoothOutput(expectedAddress) ?: error("Connect AirPods to the receiving device first")
        // Our AudioTrack is relay output, not a fresh local play intent. Without this
        // guard MediaController can send MEDIA_PLAY to a paused local app.
        synchronized(resourceLock) {
            check(!closed.get()) { "Playback stopped during setup" }
            MediaController.setRelayAudioPlaybackActive(true)
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // Receive duck events explicitly: our internal assistant-usage track is
            // intentionally excluded from local-media detection and system media ducking.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                try { onAudioFocusChanged(change) }
                catch (error: Exception) { if (!closed.get()) fail(error.message ?: "Audio interruption handling failed") }
            }, handler)
            .build()
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error("Audio focus was denied")
        }
        synchronized(resourceLock) {
            if (closed.get()) {
                audioManager.abandonAudioFocusRequest(focus)
                error("Playback stopped during setup")
            }
            focusRequest = focus
            focusHeld = true
        }
        val minimum = AudioTrack.getMinBufferSize(
            NativeOpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "Stereo Bluetooth playback format is unsupported" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Relay output is an internal receiver track, not a fresh local media
                    // start. The regular EveryPods app observes playback globally and
                    // otherwise sends MEDIA_PLAY to its current session (often Pocket Casts).
                    // USAGE_ASSISTANT is excluded by MediaController's automatic takeover
                    // classifier while this AudioTrack still routes to the selected A2DP device.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(NativeOpusCodec.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(if (tuning.playbackBufferMs == 0) maxOf(minimum * 2, framePcmBytes * 10)
                else maxOf(minimum, NativeOpusCodec.SAMPLE_RATE * 2 * NativeOpusCodec.CHANNELS * tuning.playbackBufferMs / 1_000))
            .build()
        check(output.state == AudioTrack.STATE_INITIALIZED) { "Bluetooth playback track initialization failed" }
        Log.i(TAG, "Playback buffer requested=${output.bufferSizeInFrames} frames actual=${output.bufferSizeInFrames} frames configuredMs=${tuning.playbackBufferMs} frameMs=${tuning.frameDurationMs}")
        if (!output.setPreferredDevice(bluetooth)) {
            output.release()
            error("Could not select the Bluetooth audio output")
        }
        synchronized(resourceLock) {
            if (closed.get()) {
                output.release()
                error("Playback stopped during setup")
            }
            track = output
            output.addOnRoutingChangedListener({
                val routed = output.routedDevice
                if (!closed.get() && interruption == RelayInterruption.NONE && routed != null && !isExpectedAirPods(routed)) fail("Audio route left AirPods")
            }, handler)
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
            output.play()
        }
        // Prime route selection with silence only. Captured media is not released to AudioTrack
        // until the Bluetooth route has been observed.
        output.write(ShortArray(framePcmSamples), 0, framePcmSamples, AudioTrack.WRITE_BLOCKING)
        val deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS
        while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
            if (output.routedDevice?.let(::isExpectedAirPods) == true) break
            Thread.sleep(10)
        }
        check(output.routedDevice?.let(::isExpectedAirPods) == true) {
            "AirPods route did not become active; playback was not sent to the speaker"
        }
        Log.i(TAG, "Receiver AudioTrack routed to expected Bluetooth output; type=${output.routedDevice?.type}")
        synchronized(resourceLock) {
            check(!closed.get()) { "Playback stopped during setup" }
            if (interruption != RelayInterruption.NONE) {
                output.pause()
                output.flush()
                resetPlaybackTimeline()
            }
            worker = Thread(::playFrames, "EveryPodsRelayAudioPlayback").also(Thread::start)
        }
    }

    fun offer(encodedPacket: ByteArray) {
        if (closed.get() || track == null) return
        val packet = try {
            RelayMediaPacket.decode(encodedPacket, tuning.frameDurationMs)
        } catch (error: Exception) {
            fail("Invalid incoming audio frame")
            return
        }
        receivedFrames++
        if (receivedFrames == 1L || receivedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
            Log.i(TAG, "Received media frames=$receivedFrames payloadBytes=${packet.opusPayload.size}")
        }
        val arrival = SystemClock.elapsedRealtime()
        lastArrivalMillis?.let { previous ->
            if (arrival - previous >= 100) Log.w(TAG, "Incoming audio gap=${arrival - previous}ms sequence=${packet.sequence}")
        }
        lastArrivalMillis = arrival
        synchronized(resourceLock) {
            newestSourceTimestampNanos = maxOf(newestSourceTimestampNanos, packet.timestampNanos)
            // Keep the session/capture alive, but never retain unheard media for replay.
            val offset = sourceClockOffsetNanos()
            val fresh = packet.timestampNanos > discardThroughSourceTimestampNanos &&
                (offset == null || packet.timestampNanos - offset >= resumeAtReceiverNanos)
            if (!closed.get() && interruption == RelayInterruption.NONE && fresh) jitterBuffer.offer(packet, arrival)
        }
    }

    private fun playFrames() {
        var playedFrames = 0L
        try {
            while (!closed.get()) {
                if (interruption != RelayInterruption.NONE) {
                    Thread.sleep(10)
                    continue
                }
                val epoch = synchronized(resourceLock) { playbackEpoch }
                val output = synchronized(resourceLock) {
                    if (closed.get() || interruption != RelayInterruption.NONE) null
                    else jitterBuffer.poll(SystemClock.elapsedRealtime())
                }
                if (output == null) {
                    val now = SystemClock.elapsedRealtime()
                    val noOutputSince = noOutputSinceMillis ?: now.also { noOutputSinceMillis = it }
                    val gap = now - noOutputSince
                    if (buffering && gap >= HEALTHY_GAP_RESET_MS) healthySinceMillis = null
                    if (hasPlayedRealFrame && gap >= STARVATION_MS && !buffering) {
                        buffering = true
                        healthySinceMillis = null
                        onBuffering(true)
                    }
                    Thread.sleep(2)
                    continue
                }
                noOutputSinceMillis = null
                if (buffering) {
                    val now = SystemClock.elapsedRealtime()
                    val healthySince = healthySinceMillis
                    if (healthySince == null) healthySinceMillis = now
                    else if (now - healthySince >= HEALTHY_RESUME_MS) {
                        buffering = false
                        healthySinceMillis = null
                        onBuffering(false)
                    }
                }
                val pcm = synchronized(resourceLock) {
                    if (closed.get() || interruption != RelayInterruption.NONE || playbackEpoch != epoch) return@synchronized null
                    when (output) {
                        is RelayJitterBuffer.Output.Frame -> framer.decode(output.packet.encode())
                        is RelayJitterBuffer.Output.Missing -> framer.concealLoss()
                    }
                } ?: continue
                if (!writeFully(checkNotNull(track), pcm, epoch)) continue
                synchronized(resourceLock) {
                    if (closed.get() || interruption != RelayInterruption.NONE || playbackEpoch != epoch) return@synchronized
                    writtenAudioFrames += pcm.size / NativeOpusCodec.CHANNELS
                    if (output is RelayJitterBuffer.Output.Frame && !hasPlayedRealFrame && isAudible(pcm)) {
                        hasPlayedRealFrame = true
                        Log.i(TAG, "Played first audible frame; RMS=${rmsLevel(pcm)}")
                        onFirstAudioFrame()
                    }
                }
                if (output is RelayJitterBuffer.Output.Frame) {
                    writtenFrames++
                }
                playedFrames++
                if (playedFrames == 1L || playedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
                    val audioTrack = track
                    val head = audioTrack?.playbackHeadPosition?.toLong()?.and(0xffff_ffffL) ?: writtenAudioFrames
                    val queuedMs = ((writtenAudioFrames - head).coerceAtLeast(0L) * 1_000L / NativeOpusCodec.SAMPLE_RATE)
                    Log.i(TAG, "AudioTrack realFrames=$writtenFrames playedFrames=$playedFrames queuedMs=$queuedMs underruns=${audioTrack?.underrunCount} buffer=${jitterBuffer.statistics()} routedToAirPods=${audioTrack?.routedDevice?.let(::isExpectedAirPods) == true}")
                }
                val mediaPacket = (output as? RelayJitterBuffer.Output.Frame)?.packet
                val offset = sourceClockOffsetNanos()
                if (mediaPacket != null && offset != null && playedFrames % LATENCY_SAMPLE_INTERVAL == 0L) {
                    val audioTrack = track
                    val head = audioTrack?.playbackHeadPosition?.toLong()?.and(0xffff_ffffL) ?: writtenAudioFrames
                    val queueMs = ((writtenAudioFrames - head).coerceAtLeast(0L) * 1_000L / NativeOpusCodec.SAMPLE_RATE).toInt()
                    val captureTimeOnReceiver = mediaPacket.timestampNanos - offset
                    val ageMs = ((SystemClock.elapsedRealtimeNanos() - captureTimeOnReceiver).coerceAtLeast(0L) / 1_000_000L).toInt()
                    val estimate = ageMs + queueMs
                    onDelayMeasurement(estimate, jitterBuffer.statistics().targetDelayMillis, queueMs)
                    Log.i(TAG, "Estimated capture-to-Android-output delay=${estimate}ms sampleAge=${ageMs}ms jitterTarget=${jitterBuffer.statistics().targetDelayMillis}ms outputQueue=${queueMs}ms")
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) fail(error.message ?: "Bluetooth playback failed")
        }
    }

    private fun writeFully(output: AudioTrack, pcm: ShortArray, epoch: Long): Boolean {
        var offset = 0
        while (offset < pcm.size) {
            val count = synchronized(resourceLock) {
                if (closed.get() || interruption != RelayInterruption.NONE || playbackEpoch != epoch) return false
                check(output.routedDevice?.let(::isExpectedAirPods) == true) {
                    "AirPods audio route was lost; playback stopped"
                }
                // Nonblocking writes let focus loss/Stop pause and flush without waiting
                // behind a Bluetooth output write that may itself have stopped consuming.
                output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (count < 0) error("AudioTrack write failed: $count")
            if (count == 0) Thread.sleep(2) else offset += count
        }
        return true
    }

    private fun onAudioFocusChanged(change: Int) {
        if (closed.get()) return
        Log.i(TAG, "Relay audio focus changed: $change interruption=$interruption")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                suspendPlayback(RelayInterruption.LOCAL_AUDIO)
                // Permanent loss must not cause an unsolicited restart on a late gain.
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
                focusHeld = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (interruption != RelayInterruption.LOCAL_AUDIO) suspendPlayback(RelayInterruption.TEMPORARY)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> synchronized(resourceLock) {
                ducked = true
                track?.setVolume(DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                synchronized(resourceLock) { ducked = false; track?.setVolume(1f) }
                if (interruption == RelayInterruption.TEMPORARY) resumePlayback()
            }
        }
    }

    private fun suspendPlayback(reason: RelayInterruption) {
        synchronized(resourceLock) {
            if (closed.get()) return
            interruption = reason
            playbackEpoch++
            track?.pause()
            track?.flush()
            resetPlaybackTimeline()
        }
        onInterruption(reason, false)
    }

    /** Explicit user action on either endpoint; never sends Play to a source app. */
    fun resume() {
        handler.post {
            if (closed.get() || interruption == RelayInterruption.NONE) return@post
            try {
                val request = focusRequest ?: return@post
                if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    onInterruption(interruption, true)
                    return@post
                }
                synchronized(resourceLock) {
                    if (closed.get()) {
                        audioManager.abandonAudioFocusRequest(request)
                        return@post
                    }
                    focusHeld = true
                    ducked = false
                }
                resumePlayback()
            } catch (error: Exception) {
                if (!closed.get()) fail(error.message ?: "Bluetooth playback could not resume")
            }
        }
    }

    private fun resumePlayback() {
        try {
            synchronized(resourceLock) {
                if (closed.get()) return
                check(expectedAirPodsAddress?.let(::findBluetoothOutput) != null) { "AirPods audio disconnected" }
                val output = checkNotNull(track)
                // No real media is accepted until the original Bluetooth route is verified.
                output.setVolume(if (ducked) DUCK_VOLUME else 1f)
                output.play()
                output.write(ShortArray(framePcmSamples), 0, framePcmSamples, AudioTrack.WRITE_NON_BLOCKING)
                val deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS
                while (!closed.get() && output.routedDevice?.let(::isExpectedAirPods) != true && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                check(output.routedDevice?.let(::isExpectedAirPods) == true) { "AirPods route did not resume" }
                resetPlaybackTimeline()
                codec.resetDecoder()
                discardThroughSourceTimestampNanos = newestSourceTimestampNanos
                resumeAtReceiverNanos = SystemClock.elapsedRealtimeNanos()
                interruption = RelayInterruption.NONE
                playbackEpoch++
            }
            onInterruption(RelayInterruption.NONE, false)
        } catch (error: Exception) {
            if (!closed.get()) fail(error.message ?: "Bluetooth playback could not resume")
        }
    }

    private fun resetPlaybackTimeline() {
        jitterBuffer.clear()
        hasPlayedRealFrame = false
        buffering = false
        healthySinceMillis = null
        noOutputSinceMillis = null
        writtenAudioFrames = 0L
        lastArrivalMillis = null
    }

    private fun isAudible(pcm: ShortArray): Boolean {
        return rmsLevel(pcm) >= 0.0001
    }

    private fun rmsLevel(pcm: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in pcm) {
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / pcm.size)
    }

    private fun findBluetoothOutput(expectedAddress: String): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
            isBluetoothOutput(device) && device.address.equals(expectedAddress, ignoreCase = true)
        }

    private fun isExpectedAirPods(device: AudioDeviceInfo): Boolean =
        isBluetoothOutput(device) && !expectedAirPodsAddress.isNullOrBlank() &&
            device.address.equals(expectedAirPodsAddress, ignoreCase = true)

    private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_HEARING_AID

    private fun fail(reason: String) {
        if (closed.get()) return
        onFailure(reason)
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(resourceLock) {
            MediaController.setRelayAudioPlaybackActive(false)
            jitterBuffer.clear()
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            val output = track
            track = null
            if (output != null) {
                runCatching { output.pause() }
                runCatching { output.flush() }
                runCatching { output.stop() }
                output.release()
            }
            if (focusHeld) {
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
                focusHeld = false
            }
            focusRequest = null
            codec.close()
            handlerThread.quitSafely()
            worker?.interrupt()
            worker = null
        }
    }

    companion object {
        private const val TAG = "RelayAudioPlayback"
        private const val DUCK_VOLUME = 0.2f
        private const val ROUTE_TIMEOUT_MS = 1_500L
        private const val STARVATION_MS = 200L
        private const val HEALTHY_GAP_RESET_MS = 100L
        private const val HEALTHY_RESUME_MS = 1_000L
        private const val DIAGNOSTIC_FRAME_INTERVAL = 50L
        private const val LATENCY_SAMPLE_INTERVAL = 10L
    }
}
