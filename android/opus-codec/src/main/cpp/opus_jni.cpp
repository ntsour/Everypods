#include <jni.h>
#include <opus.h>

#include <cstdint>
#include <vector>

namespace {
constexpr opus_int32 kSampleRate = 48'000;
constexpr int kChannels = 2;
constexpr int kMaxFrameSamplesPerChannel = 5'760; // Opus maximum 120 ms at 48 kHz.
constexpr int kMaxPacketBytes = 4'096;

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_automated_ventures_everypods_opus_NativeOpusCodec_nativeCreateEncoder(JNIEnv* env, jobject, jint frameDurationMs, jint bitrate, jboolean lowDelay) {
    if ((frameDurationMs != 10 && frameDurationMs != 20) || bitrate < 64'000 || bitrate > 320'000) {
        throwIllegalArgument(env, "Unsupported Opus relay configuration");
        return 0;
    }
    int error = OPUS_OK;
    OpusEncoder* encoder = opus_encoder_create(kSampleRate, kChannels,
        lowDelay == JNI_TRUE ? OPUS_APPLICATION_RESTRICTED_LOWDELAY : OPUS_APPLICATION_AUDIO, &error);
    if (encoder == nullptr || error != OPUS_OK) {
        throwIllegalState(env, opus_strerror(error));
        return 0;
    }
    const int config[] = {
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)),
        opus_encoder_ctl(encoder, OPUS_SET_VBR(1)),
        opus_encoder_ctl(encoder, OPUS_SET_VBR_CONSTRAINT(1)),
    };
    for (int result : config) {
        if (result != OPUS_OK) {
            opus_encoder_destroy(encoder);
            throwIllegalState(env, opus_strerror(result));
            return 0;
        }
    }
    return reinterpret_cast<jlong>(encoder);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_automated_ventures_everypods_opus_NativeOpusCodec_nativeCreateDecoder(JNIEnv* env, jobject) {
    int error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(kSampleRate, kChannels, &error);
    if (decoder == nullptr || error != OPUS_OK) {
        throwIllegalState(env, opus_strerror(error));
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_automated_ventures_everypods_opus_NativeOpusCodec_nativeEncode(
    JNIEnv* env, jobject, jlong handle, jshortArray pcm, jint samplesPerChannel) {
    if (handle == 0 || pcm == nullptr || (samplesPerChannel != 480 && samplesPerChannel != 960) ||
        env->GetArrayLength(pcm) != samplesPerChannel * kChannels) {
        throwIllegalArgument(env, "Opus encoder received an invalid relay frame");
        return nullptr;
    }
    jshort* input = env->GetShortArrayElements(pcm, nullptr);
    if (input == nullptr) return nullptr;
    unsigned char output[kMaxPacketBytes];
    const opus_int32 size = opus_encode(
        reinterpret_cast<OpusEncoder*>(handle), reinterpret_cast<const opus_int16*>(input),
        samplesPerChannel, output, kMaxPacketBytes);
    env->ReleaseShortArrayElements(pcm, input, JNI_ABORT);
    if (size < 0) {
        throwIllegalState(env, opus_strerror(size));
        return nullptr;
    }
    jbyteArray packet = env->NewByteArray(size);
    if (packet != nullptr) env->SetByteArrayRegion(packet, 0, size, reinterpret_cast<const jbyte*>(output));
    return packet;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_io_automated_ventures_everypods_opus_NativeOpusCodec_nativeDecode(
    JNIEnv* env, jobject, jlong handle, jbyteArray packet, jint samplesPerChannel) {
    if (handle == 0) {
        throwIllegalArgument(env, "Opus decoder is closed");
        return nullptr;
    }
    if (samplesPerChannel != 480 && samplesPerChannel != 960) {
        throwIllegalArgument(env, "Invalid Opus decode frame size");
        return nullptr;
    }
    const jsize size = packet == nullptr ? 0 : env->GetArrayLength(packet);
    if (size > kMaxPacketBytes) {
        throwIllegalArgument(env, "Opus packet exceeds the configured bound");
        return nullptr;
    }
    jbyte* input = packet == nullptr ? nullptr : env->GetByteArrayElements(packet, nullptr);
    if (packet != nullptr && input == nullptr) return nullptr;
    std::vector<opus_int16> output(kMaxFrameSamplesPerChannel * kChannels);
    const int samples = opus_decode(
        reinterpret_cast<OpusDecoder*>(handle),
        input == nullptr ? nullptr : reinterpret_cast<const unsigned char*>(input),
        size, output.data(), samplesPerChannel, 0);
    if (packet != nullptr && input != nullptr) env->ReleaseByteArrayElements(packet, input, JNI_ABORT);
    if (samples < 0) {
        throwIllegalState(env, opus_strerror(samples));
        return nullptr;
    }
    jshortArray pcm = env->NewShortArray(samples * kChannels);
    if (pcm != nullptr) env->SetShortArrayRegion(pcm, 0, samples * kChannels, reinterpret_cast<const jshort*>(output.data()));
    return pcm;
}

extern "C" JNIEXPORT void JNICALL
Java_io_automated_ventures_everypods_opus_NativeOpusCodec_nativeDestroyEncoder(JNIEnv*, jobject, jlong handle) {
    if (handle != 0) opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_io_automated_ventures_everypods_opus_NativeOpusCodec_nativeDestroyDecoder(JNIEnv*, jobject, jlong handle) {
    if (handle != 0) opus_decoder_destroy(reinterpret_cast<OpusDecoder*>(handle));
}
