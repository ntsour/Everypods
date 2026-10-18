package io.automated.ventures.everypods.relay

/** Pinned relay audio format (design doc): Opus 48 kHz stereo, 20 ms, 192 kbit/s constrained VBR. */
object RelayAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // per channel: 960
    const val BITRATE = 192_000
    const val MAX_OPUS_PACKET = 1275
}

internal object OpusNative {
    init { System.loadLibrary("everypods_opus") }
    @JvmStatic external fun encoderCreate(rate: Int, channels: Int, bitrate: Int, complexity: Int): Long
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int
    @JvmStatic external fun encoderDestroy(handle: Long)
    @JvmStatic external fun decoderCreate(rate: Int, channels: Int): Long
    @JvmStatic external fun decode(handle: Long, data: ByteArray?, len: Int, pcm: ShortArray, frameSize: Int, fec: Int): Int
    @JvmStatic external fun decoderDestroy(handle: Long)
    @JvmStatic external fun version(): String
}

class OpusException(code: Int, op: String) : RuntimeException("opus $op failed: $code")

/** Not thread-safe; one encoder per capture thread. */
class OpusEncoder(
    private val sampleRate: Int = RelayAudioFormat.SAMPLE_RATE,
    private val channels: Int = RelayAudioFormat.CHANNELS,
    bitrate: Int = RelayAudioFormat.BITRATE,
    complexity: Int = 10,
) : AutoCloseable {
    private var handle = OpusNative.encoderCreate(sampleRate, channels, bitrate, complexity)
        .also { if (it == 0L) throw OpusException(-1, "encoderCreate") }

    /** [pcm] is interleaved, frameSize*channels samples. Returns encoded bytes. */
    fun encode(pcm: ShortArray, frameSize: Int = RelayAudioFormat.FRAME_SAMPLES): ByteArray {
        check(handle != 0L) { "closed" }
        require(pcm.size >= frameSize * channels) { "short PCM frame" }
        val out = ByteArray(RelayAudioFormat.MAX_OPUS_PACKET)
        val n = OpusNative.encode(handle, pcm, frameSize, out)
        if (n < 0) throw OpusException(n, "encode")
        return out.copyOf(n)
    }

    override fun close() {
        if (handle != 0L) { OpusNative.encoderDestroy(handle); handle = 0L }
    }

    companion object { fun libraryVersion(): String = OpusNative.version() }
}

class OpusDecoder(
    sampleRate: Int = RelayAudioFormat.SAMPLE_RATE,
    private val channels: Int = RelayAudioFormat.CHANNELS,
) : AutoCloseable {
    private var handle = OpusNative.decoderCreate(sampleRate, channels)
        .also { if (it == 0L) throw OpusException(-1, "decoderCreate") }

    /** Decodes [packet]; null means the packet was lost (PLC). Returns interleaved PCM. */
    fun decode(packet: ByteArray?, frameSize: Int = RelayAudioFormat.FRAME_SAMPLES, fec: Boolean = false): ShortArray {
        check(handle != 0L) { "closed" }
        val pcm = ShortArray(frameSize * channels)
        val n = OpusNative.decode(handle, packet, packet?.size ?: 0, pcm, frameSize, if (fec) 1 else 0)
        if (n < 0) throw OpusException(n, "decode")
        return if (n == frameSize) pcm else pcm.copyOf(n * channels)
    }

    override fun close() {
        if (handle != 0L) { OpusNative.decoderDestroy(handle); handle = 0L }
    }
}
