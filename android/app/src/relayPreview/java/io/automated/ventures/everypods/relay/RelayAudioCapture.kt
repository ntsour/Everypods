/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.opus.NativeOpusCodec
import io.automated.ventures.everypods.relay.transport.RelayOpusFramer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Source-side all-eligible-apps MediaProjection capture at the pinned Opus input format. */
class RelayAudioCapture(
    context: Context,
    private val onEncodedFrame: (ByteArray) -> Boolean,
    private val onFrameTiming: (encodeMillis: Int, sendCallMillis: Int, captureAgeMillis: Int?, peakReadMillis: Int) -> Unit = { _, _, _, _ -> },
    private val tuning: RelayAudioTuning = RelayAudioTuning.DEFAULT,
    private val muteLocalPlayback: Boolean,
    private val onAudioActivity: (Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean(false)
    private val codec = NativeOpusCodec(tuning.frameDurationMs, tuning.bitrateKbps, tuning.lowDelayMode)
    private val framer = RelayOpusFramer(codec)
    private val frameSamples = NativeOpusCodec.SAMPLE_RATE * tuning.frameDurationMs / 1_000 * NativeOpusCodec.CHANNELS
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var savedVolume = -1
    private var lastActivity: Boolean? = null
    private var capturedFrames = 0L
    @Volatile var isRunning: Boolean = false
        private set

    fun start(resultCode: Int, consentData: Intent) {
        if (appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(appContext.getString(io.automated.ventures.everypods.R.string.audio_relay_capture_failed))
        }
        check(!isRunning && !closing.get()) { "Audio capture is already active" }
        val capture = appContext.getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, consentData)
            ?: error("Android did not grant playback capture")
        projection = capture
        capture.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!closing.get()) onFailure("Android ended playback capture")
            }
        }, handler)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(capture)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            NativeOpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "Stereo playback capture format is unsupported" }
        val input = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(NativeOpusCodec.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(if (tuning.captureBufferMs == 0) maxOf(minimum * 2, frameSamples * 2 * 4)
                else maxOf(minimum, NativeOpusCodec.SAMPLE_RATE * NativeOpusCodec.CHANNELS * 2 * tuning.captureBufferMs / 1_000))
            .build()
        check(input.state == AudioRecord.STATE_INITIALIZED) { "Playback capture recorder initialization failed" }
        Log.i(TAG, "Capture buffer requested=${input.bufferSizeInFrames} frames actual=${input.bufferSizeInFrames} frames configuredMs=${tuning.captureBufferMs} frameMs=${tuning.frameDurationMs}")
        recorder = input
        if (muteLocalPlayback) muteLocalMedia()
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Playback capture did not start" }
        isRunning = true
        worker = Thread(::captureLoop, "EveryPodsRelayAudioCapture").also(Thread::start)
    }

    private fun captureLoop() {
        val input = checkNotNull(recorder)
        val frame = ShortArray(frameSamples)
        val audioTimestamp = AudioTimestamp()
        val samplesPerChannel = NativeOpusCodec.SAMPLE_RATE * tuning.frameDurationMs / 1_000
        var offset = 0
        var readWaitNanos = 0L
        var peakReadNanos = 0L
        var captureAgeTotalNanos = 0L
        var captureAgeSamples = 0
        var silenceFrames = 0
        try {
            while (!closing.get()) {
                val readStarted = SystemClock.elapsedRealtimeNanos()
                val count = input.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                val readCompleted = SystemClock.elapsedRealtimeNanos()
                readWaitNanos += readCompleted - readStarted
                if (count < 0) error("Playback capture read failed: $count")
                if (count == 0) continue
                offset += count
                if (offset != frame.size) continue
                offset = 0
                peakReadNanos = maxOf(peakReadNanos, readWaitNanos)
                readWaitNanos = 0L
                // AudioRecord can return several buffered frames in a burst. The
                // media clock advances by sample count, not thread wake-up time.
                val firstSamplePosition = capturedFrames * samplesPerChannel
                val hasTimestamp = input.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                val timestampNanos = if (hasTimestamp) {
                    audioTimestamp.nanoTime + (firstSamplePosition - audioTimestamp.framePosition) * 1_000_000_000L / NativeOpusCodec.SAMPLE_RATE
                } else {
                    // Best-effort fallback when the capture HAL does not expose an audio timestamp.
                    SystemClock.elapsedRealtimeNanos() - tuning.frameDurationMs * 1_000_000L
                }.coerceAtLeast(0L)
                if (hasTimestamp && readCompleted >= timestampNanos) {
                    captureAgeTotalNanos += readCompleted - timestampNanos
                    captureAgeSamples++
                }
                val encodeStartedNanos = SystemClock.elapsedRealtimeNanos()
                val packet = framer.encode(frame, timestampNanos)
                val encodeMillis = ((SystemClock.elapsedRealtimeNanos() - encodeStartedNanos) / 1_000_000L).toInt()
                val sendStartedNanos = SystemClock.elapsedRealtimeNanos()
                val sent = onEncodedFrame(packet)
                val sendMillis = ((SystemClock.elapsedRealtimeNanos() - sendStartedNanos) / 1_000_000L).toInt()
                capturedFrames++
                // Send media before UI/control notifications; short silent passages must
                // not flood the reliable channel or flip playback state every frame.
                val rms = rmsLevel(frame)
                silenceFrames = if (rms >= RMS_ACTIVITY_THRESHOLD) 0 else silenceFrames + 1
                val active = rms >= RMS_ACTIVITY_THRESHOLD || (lastActivity == true && silenceFrames * tuning.frameDurationMs < 250)
                if (lastActivity != active) {
                    lastActivity = active
                    onAudioActivity(active)
                }
                if (capturedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
                    val ageMs = if (captureAgeSamples > 0) (captureAgeTotalNanos / captureAgeSamples / 1_000_000L).toInt() else null
                    val peakReadMs = (peakReadNanos / 1_000_000L).toInt()
                    onFrameTiming(encodeMillis, sendMillis, ageMs, peakReadMs)
                    Log.i(TAG, "Capture delivery meanAgeMs=$ageMs peakReadMs=$peakReadMs encodeMs=$encodeMillis sendCallMs=$sendMillis bufferFrames=${input.bufferSizeInFrames}")
                    peakReadNanos = 0L
                    captureAgeTotalNanos = 0L
                    captureAgeSamples = 0
                }
                if (capturedFrames == 1L || capturedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
                    Log.i(TAG, "Playback capture frames=$capturedFrames rms=$rms transportSent=$sent")
                }
            }
        } catch (error: Exception) {
            if (!closing.get()) onFailure(error.message ?: "Playback capture failed")
        } finally {
            frame.fill(0)
        }
    }

    private fun rmsLevel(frame: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in frame) {
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / frame.size)
    }

    private fun muteLocalMedia() {
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (savedVolume > 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        // Only restore if the stream is still at the zero value EveryPods set. A user's
        // manual volume change while relay is active takes precedence.
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        }
        savedVolume = -1
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        isRunning = false
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        worker?.interrupt()
        worker = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { restoreVolume() }
        codec.close()
    }

    companion object {
        private const val TAG = "RelayAudioCapture"
        private const val RMS_ACTIVITY_THRESHOLD = 0.0001
        private const val DIAGNOSTIC_FRAME_INTERVAL = 50L
    }
}
