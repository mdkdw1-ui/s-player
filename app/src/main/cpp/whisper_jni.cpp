#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---------- 버전/시스템 정보 ----------
extern "C" JNIEXPORT jstring JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "unknown");
}

// ---------- 모델 로드 ----------
extern "C" JNIEXPORT jlong JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("nativeInit: %s", path);

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init 실패");
        return 0;
    }
    LOGI("whisper_init 성공");
    return reinterpret_cast<jlong>(ctx);
}

// ---------- 모델 해제 ----------
extern "C" JNIEXPORT void JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeRelease(
        JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr == 0) return;
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    whisper_free(ctx);
    LOGI("whisper_free 완료");
}

// ---------- 콜백 홀더 ----------
struct CallbackHolder {
    jobject callback;
    jmethodID onSegment;
    jmethodID onProgress;
    jmethodID onComplete;
    std::mutex mtx;
};

// whisper 가 세그먼트 하나 처리할 때마다 호출
static void new_segment_callback(whisper_context *ctx,
                                 whisper_state *state,
                                 int n_new,
                                 void *user_data) {
    auto *holder = reinterpret_cast<CallbackHolder *>(user_data);
    if (!holder) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    std::lock_guard<std::mutex> lock(holder->mtx);

    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i); // centiseconds
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        jstring jtext = env->NewStringUTF(text ? text : "");
        env->CallVoidMethod(holder->callback, holder->onSegment,
                            (jlong)(t0 * 10), (jlong)(t1 * 10), jtext);
        env->DeleteLocalRef(jtext);

        // 진행률 (0~100) — 정확한 전체 길이를 모르면 세그먼트 수 기반으로 대략
        int progress = (i + 1) * 100 / (n > 0 ? n : 1);
        env->CallVoidMethod(holder->callback, holder->onProgress, (jint) progress);
    }

    if (attached) g_vm->DetachCurrentThread();
}

// ---------- 트랜스크립션 ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject,
        jlong ctxPtr,
        jstring wavPath,
        jstring lang,
        jint threads,
        jobject callback) {

    if (ctxPtr == 0) {
        LOGE("ctxPtr == 0");
        return -1;
    }
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);

    const char *path = env->GetStringUTFChars(wavPath, nullptr);
    const char *langStr = env->GetStringUTFChars(lang, nullptr);
    LOGI("nativeTranscribe: %s (lang=%s, threads=%d)", path, langStr, threads);

    // 콜백 준비
    jclass cls = env->GetObjectClass(callback);
    CallbackHolder holder{};
    holder.callback = env->NewGlobalRef(callback);
    holder.onSegment = env->GetMethodID(cls, "onSegment", "(JJLjava/lang/String;)V");
    holder.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    holder.onComplete = env->GetMethodID(cls, "onComplete", "()V");

    if (!holder.onSegment || !holder.onProgress || !holder.onComplete) {
        LOGE("콜백 메서드 못 찾음");
        env->ReleaseStringUTFChars(wavPath, path);
        env->ReleaseStringUTFChars(lang, langStr);
        env->DeleteGlobalRef(holder.callback);
        return -2;
    }

    // 파라미터
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = langStr;
    params.n_threads = threads > 0 ? threads : 4;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.new_segment_callback = new_segment_callback;
    params.new_segment_callback_user_data = &holder;

    int ret = whisper_full(ctx, params, path, 0);
    if (ret != 0) {
        LOGE("whisper_full 실패: %d", ret);
    } else {
        LOGI("whisper_full 완료");
    }

    env->CallVoidMethod(holder.callback, holder.onComplete);

    env->ReleaseStringUTFChars(wavPath, path);
    env->ReleaseStringUTFChars(lang, langStr);
    env->DeleteGlobalRef(holder.callback);
    return ret;
}
