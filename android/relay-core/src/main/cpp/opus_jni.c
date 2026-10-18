// JNI wrapper around libopus for the Wi-Fi audio relay. Handles are native pointers
// owned by OpusEncoder/OpusDecoder in Kotlin and released exactly once via release().
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(cls, name) Java_io_automated_ventures_everypods_relay_##cls##_##name

JNIEXPORT jlong JNICALL FN(OpusNative, encoderCreate)(JNIEnv *env, jclass c,
        jint rate, jint channels, jint bitrate, jint complexity) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(rate, channels, OPUS_APPLICATION_AUDIO, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(1));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(5));
    return (jlong)(intptr_t)enc;
}

JNIEXPORT jint JNICALL FN(OpusNative, encode)(JNIEnv *env, jclass c, jlong h,
        jshortArray pcm, jint frameSize, jbyteArray out) {
    if (h == 0) return OPUS_INVALID_STATE;
    jsize outLen = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = opus_encode((OpusEncoder *)(intptr_t)h, in, frameSize, (unsigned char *)o, outLen);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, n > 0 ? 0 : JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL FN(OpusNative, encoderDestroy)(JNIEnv *env, jclass c, jlong h) {
    if (h != 0) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

JNIEXPORT jlong JNICALL FN(OpusNative, decoderCreate)(JNIEnv *env, jclass c, jint rate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(rate, channels, &err);
    if (err != OPUS_OK || dec == NULL) return 0;
    return (jlong)(intptr_t)dec;
}

// data == null -> packet-loss concealment for frameSize samples. fec=1 decodes the
// in-band FEC of the NEXT packet to recover the lost one.
JNIEXPORT jint JNICALL FN(OpusNative, decode)(JNIEnv *env, jclass c, jlong h,
        jbyteArray data, jint len, jshortArray pcm, jint frameSize, jint fec) {
    if (h == 0) return OPUS_INVALID_STATE;
    jbyte *d = data ? (*env)->GetByteArrayElements(env, data, NULL) : NULL;
    jshort *o = (*env)->GetShortArrayElements(env, pcm, NULL);
    int n = opus_decode((OpusDecoder *)(intptr_t)h, (const unsigned char *)d, d ? len : 0,
            o, frameSize, fec);
    if (d) (*env)->ReleaseByteArrayElements(env, data, d, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, pcm, o, n > 0 ? 0 : JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL FN(OpusNative, decoderDestroy)(JNIEnv *env, jclass c, jlong h) {
    if (h != 0) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}

JNIEXPORT jstring JNICALL FN(OpusNative, version)(JNIEnv *env, jclass c) {
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
