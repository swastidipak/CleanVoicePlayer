// JNI bridge: Java <-> RNNoise (xiph.org recurrent-neural-network speech denoiser, BSD license)
#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL
Java_com_cleanvoice_player_dsp_RNNoise_nativeCreate(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_com_cleanvoice_player_dsp_RNNoise_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}

/* Denoises 480 samples (10 ms @ 48 kHz) in place. Input/output range -1..1.
   Returns RNNoise's voice-activity probability (0..1) for the frame. */
JNIEXPORT jfloat JNICALL
Java_com_cleanvoice_player_dsp_RNNoise_nativeProcess(JNIEnv *env, jclass cls, jlong handle, jfloatArray buf) {
    (void) cls;
    float in[FRAME], out[FRAME];
    (*env)->GetFloatArrayRegion(env, buf, 0, FRAME, in);
    for (int i = 0; i < FRAME; i++) in[i] *= 32768.0f;
    float vad = rnnoise_process_frame((DenoiseState *) (intptr_t) handle, out, in);
    for (int i = 0; i < FRAME; i++) out[i] *= (1.0f / 32768.0f);
    (*env)->SetFloatArrayRegion(env, buf, 0, FRAME, out);
    return vad;
}
