#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(whisper_version());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeInit(
        JNIEnv *env, jobject /* this */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("nativeInit: %s", path);

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init 실패");
        return JNI_FALSE;
    }
    LOGI("whisper_init 성공");
    return JNI_TRUE;
}
