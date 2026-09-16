#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

struct WavData {
    std::vector<float> samples;
    int sampleRate = 0;
    int channels = 0;
    bool ok = false;
};

static bool read_wav_file(const char *path, WavData &out) {
    FILE *f = fopen(path, "rb");
    if (!f) { LOGE("WAV 열기 실패: %s", path); return false; }

    char riff[4]; uint32_t fileSize; char wave[4];
    if (fread(riff, 1, 4, f) != 4 || memcmp(riff, "RIFF", 4) != 0) { fclose(f); return false; }
    if (fread(&fileSize, 4, 1, f) != 1) { fclose(f); return false; }
    if (fread(wave, 1, 4, f) != 4 || memcmp(wave, "WAVE", 4) != 0) { fclose(f); return false; }

    int channels = 0, sampleRate = 0, bitsPerSample = 0;
    std::vector<int16_t> pcm;

    while (true) {
        char chunkId[4]; uint32_t chunkSize;
        if (fread(chunkId, 1, 4, f) != 4) break;
        if (fread(&chunkSize, 4, 1, f) != 1) break;

        if (memcmp(chunkId, "fmt ", 4) == 0) {
            uint16_t audioFormat, numChannels, blockAlign, bps;
            uint32_t sr, byteRate;
            if (fread(&audioFormat, 2, 1, f) != 1) break;
            if (fread(&numChannels, 2, 1, f) != 1) break;
            if (fread(&sr, 4, 1, f) != 1) break;
            if (fread(&byteRate, 4, 1, f) != 1) break;
            if (fread(&blockAlign, 2, 1, f) != 1) break;
            if (fread(&bps, 2, 1, f) != 1) break;
            channels = numChannels; sampleRate = sr; bitsPerSample = bps;
            if (chunkSize > 16) fseek(f, chunkSize - 16, SEEK_CUR);
        } else if (memcmp(chunkId, "data", 4) == 0) {
            uint32_t nSamples = chunkSize / 2;
            pcm.resize(nSamples);
            fread(pcm.data(), 2, nSamples, f);
            if (chunkSize % 2) fseek(f, 1, SEEK_CUR);
        } else {
            fseek(f, chunkSize, SEEK_CUR);
        }
    }
    fclose(f);

    if (channels == 0 || sampleRate == 0 || bitsPerSample != 16 || pcm.empty()) {
        LOGE("WAV 형식 오류: ch=%d sr=%d bps=%d n=%zu", channels, sampleRate, bitsPerSample, pcm.size());
        return false;
    }

    out.samples.reserve(pcm.size() / channels);
    for (size_t i = 0; i + channels <= pcm.size(); i += channels) {
        float sum = 0.f;
        for (int c = 0; c < channels; c++) sum += (float) pcm[i + c] / 32768.0f;
        out.samples.push_back(sum / channels);
    }
    out.sampleRate = sampleRate; out.channels = channels; out.ok = true;
    LOGI("WAV 읽음: %zu samples, %d Hz, %d ch", out.samples.size(), sampleRate, channels);
    return true;
}

// JNI 로그를 Kotlin 콜백으로 전달하는 헬퍼
static void kotlin_log(JNIEnv *env, jobject callback, jmethodID method, const char *msg) {
    if (!callback || !method) return;
    jstring jmsg = env->NewStringUTF(msg);
    env->CallVoidMethod(callback, method, jmsg);
    env->DeleteLocalRef(jmsg);
}

struct CallbackHolder {
    jobject callback;
    jmethodID onSegment;
    jmethodID onProgress;
    jmethodID onComplete;
    jmethodID onLog;
    std::mutex mtx;
    int segments_seen = 0;
};

static void new_segment_callback(whisper_context *ctx,
                                 whisper_state *,
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
    holder->segments_seen = n;
    {
        char buf[128];
        snprintf(buf, sizeof(buf), "JNI 콜백: n=%d, n_new=%d", n, n_new);
        kotlin_log(env, holder->callback, holder->onLog, buf);
    }

    for (int i = n - n_new; i < n; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);

        jstring jtext = env->NewStringUTF(text ? text : "");
        env->CallVoidMethod(holder->callback, holder->onSegment,
                            (jlong)(t0 * 10), (jlong)(t1 * 10), jtext);
        env->DeleteLocalRef(jtext);

        int progress = (i + 1) * 100 / (n > 0 ? n : 1);
        env->CallVoidMethod(holder->callback, holder->onProgress, (jint) progress);
    }

    if (attached) g_vm->DetachCurrentThread();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "unknown");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("nativeInit: %s", path);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) { LOGE("whisper_init 실패"); return 0; }
    LOGI("whisper_init 성공");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeRelease(
        JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr == 0) return;
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    whisper_free(ctx);
    LOGI("whisper_free 완료");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mdkdw1_splayer_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject,
        jlong ctxPtr, jstring wavPath, jstring lang, jint threads, jobject callback) {

    // 콜백 준비 (로그부터 찍기 위해 먼저)
    jclass cls = env->GetObjectClass(callback);
    jmethodID onLog = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");

    kotlin_log(env, callback, onLog, "JNI: nativeTranscribe 진입");

    if (ctxPtr == 0) { kotlin_log(env, callback, onLog, "JNI: ctxPtr==0"); return -1; }
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);

    const char *path = env->GetStringUTFChars(wavPath, nullptr);
    const char *langStr = env->GetStringUTFChars(lang, nullptr);

    {
        char buf[512];
        snprintf(buf, sizeof(buf), "JNI: WAV 파싱 시작: %s (lang=%s, threads=%d)", path, langStr, threads);
        kotlin_log(env, callback, onLog, buf);
    }

    WavData wav;
    if (!read_wav_file(path, wav)) {
        kotlin_log(env, callback, onLog, "JNI: WAV 파싱 실패");
        env->ReleaseStringUTFChars(wavPath, path);
        env->ReleaseStringUTFChars(lang, langStr);
        return -3;
    }

    {
        char buf[256];
        snprintf(buf, sizeof(buf), "JNI: WAV OK %.2f초 %dHz %dch",
                 (double) wav.samples.size() / wav.sampleRate, wav.sampleRate, wav.channels);
        kotlin_log(env, callback, onLog, buf);
    }

    CallbackHolder holder{};
    holder.callback = env->NewGlobalRef(callback);
    holder.onSegment = env->GetMethodID(cls, "onSegment", "(JJLjava/lang/String;)V");
    holder.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    holder.onComplete = env->GetMethodID(cls, "onComplete", "()V");
    holder.onLog = onLog;

    if (!holder.onSegment || !holder.onProgress || !holder.onComplete) {
        kotlin_log(env, callback, onLog, "JNI: 콜백 메서드 못 찾음");
        env->ReleaseStringUTFChars(wavPath, path);
        env->ReleaseStringUTFChars(lang, langStr);
        env->DeleteGlobalRef(holder.callback);
        return -2;
    }

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

    kotlin_log(env, callback, onLog, "JNI: whisper_full 시작");

    int ret = whisper_full(ctx, params, wav.samples.data(), (int) wav.samples.size());

    {
        char buf[128];
        snprintf(buf, sizeof(buf), "JNI: whisper_full 종료 ret=%d, 세그먼트=%d",
                 ret, whisper_full_n_segments(ctx));
        kotlin_log(env, callback, onLog, buf);
    }

    env->CallVoidMethod(holder.callback, holder.onComplete);
    env->ReleaseStringUTFChars(wavPath, path);
    env->ReleaseStringUTFChars(lang, langStr);
    env->DeleteGlobalRef(holder.callback);
    return ret;
}
