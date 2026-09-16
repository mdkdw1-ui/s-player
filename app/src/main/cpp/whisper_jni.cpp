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
    return JNI_VERSION_1_6;
}

// ---------------- WAV 파서 ----------------
// 16-bit PCM mono 16kHz WAV 만 지원 (우리가 그렇게 만들 예정)
struct WavData {
    std::vector<float> samples;
    int sampleRate = 0;
    int channels = 0;
    bool ok = false;
};

static bool read_wav_file(const char *path, WavData &out) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        LOGE("WAV 열기 실패: %s", path);
        return false;
    }

    char riff[4];
    uint32_t fileSize;
    char wave[4];
    if (fread(riff, 1, 4, f) != 4 || memcmp(riff, "RIFF", 4) != 0) {
        LOGE("RIFF 헤더 아님");
        fclose(f); return false;
    }
    if (fread(&fileSize, 4, 1, f) != 1) { fclose(f); return false; }
    if (fread(wave, 1, 4, f) != 4 || memcmp(wave, "WAVE", 4) != 0) {
        LOGE("WAVE 헤더 아님");
        fclose(f); return false;
    }

    int channels = 0, sampleRate = 0, bitsPerSample = 0;
    std::vector<int16_t> pcm;

    while (true) {
        char chunkId[4];
        uint32_t chunkSize;
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
            channels = numChannels;
            sampleRate = sr;
            bitsPerSample = bps;
            // 나머지 skip
            if (chunkSize > 16) fseek(f, chunkSize - 16, SEEK_CUR);
        } else if (memcmp(chunkId, "data", 4) == 0) {
            uint32_t nSamples = chunkSize / 2; // 16bit
            pcm.resize(nSamples);
            if (fread(pcm.data(), 2, nSamples, f) != nSamples) {
                LOGE("data 청크 읽기 실패");
                fclose(f); return false;
            }
            // 홀수 바이트 남으면 skip
            if (chunkSize % 2) fseek(f, 1, SEEK_CUR);
        } else {
            // 기타 청크 skip
            fseek(f, chunkSize, SEEK_CUR);
        }
    }
    fclose(f);

    if (channels == 0 || sampleRate == 0 || bitsPerSample != 16 || pcm.empty()) {
        LOGE("WAV 형식 오류: ch=%d sr=%d bps=%d n=%zu",
             channels, sampleRate, bitsPerSample, pcm.size());
        return false;
    }

    // mono 다운믹스 + float 변환
    out.samples.reserve(pcm.size() / channels);
    for (size_t i = 0; i + channels <= pcm.size(); i += channels) {
        float sum = 0.f;
        for (int c = 0; c < channels; c++) {
            sum += (float) pcm[i + c] / 32768.0f;
        }
        out.samples.push_back(sum / channels);
    }
    out.sampleRate = sampleRate;
    out.channels = channels;
    out.ok = true;
    LOGI("WAV 읽음: %zu samples, %d Hz, %d ch", out.samples.size(), sampleRate, channels);
    return true;
}

// ---------------- 콜백 ----------------
struct CallbackHolder {
    jobject callback;
    jmethodID onSegment;
    jmethodID onProgress;
    jmethodID onComplete;
    int totalSegments;
    std::mutex mtx;
};

static void new_segment_callback(whisper_context *ctx,
                                 whisper_state * /*state*/,
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

        int progress = (i + 1) * 100 / (n > 0 ? n : 1);
        env->CallVoidMethod(holder->callback, holder->onProgress, (jint) progress);
    }

    if (attached) g_vm->DetachCurrentThread();
}

// ---------------- API ----------------
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

    if (ctx == nullptr) {
        LOGE("whisper_init 실패");
        return 0;
    }
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
        jlong ctxPtr,
        jstring wavPath,
        jstring lang,
        jint threads,
        jobject callback) {

    if (ctxPtr == 0) { LOGE("ctxPtr == 0"); return -1; }
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);

    const char *path = env->GetStringUTFChars(wavPath, nullptr);
    const char *langStr = env->GetStringUTFChars(lang, nullptr);
    LOGI("nativeTranscribe: %s (lang=%s, threads=%d)", path, langStr, threads);

    WavData wav;
    if (!read_wav_file(path, wav)) {
        env->ReleaseStringUTFChars(wavPath, path);
        env->ReleaseStringUTFChars(lang, langStr);
        return -3;
    }

    // whisper 는 16kHz 를 기대. 다른 샘플레이트면 경고만.
    if (wav.sampleRate != WHISPER_SAMPLE_RATE) {
        LOGI("경고: WAV sampleRate=%d, whisper 는 %d 기대",
             wav.sampleRate, WHISPER_SAMPLE_RATE);
    }

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

    int ret = whisper_full(ctx, params, wav.samples.data(), (int) wav.samples.size());
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
