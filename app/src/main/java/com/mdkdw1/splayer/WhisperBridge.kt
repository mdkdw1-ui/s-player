package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WhisperBridge {
    private const val TAG = "WhisperBridge"

    init {
        System.loadLibrary("splayer_whisper")
        LogBus.log(TAG, "라이브러리 로드 완료")
    }

    interface SegmentCallback {
        fun onSegment(startMs: Long, endMs: Long, text: String)
        fun onProgress(percent: Int)
        fun onComplete()
        fun onLog(msg: String)
        fun onLanguage(langCode: String) = Unit   // 기본 no-op
    }

    external fun nativeSystemInfo(): String
    external fun nativeInit(modelPath: String): Long
    external fun nativeRelease(ctxPtr: Long)
    external fun nativeTranscribe(
        ctxPtr: Long,
        wavPath: String,
        lang: String,
        threads: Int,
        callback: SegmentCallback
    ): Int

    suspend fun transcribe(
        modelPath: String,
        wavPath: String,
        language: String = "auto",
        threads: Int = 4,
        callback: SegmentCallback
    ): Boolean = withContext(Dispatchers.Default) {
        LogBus.log(TAG, "init 시작 (threads=$threads)")
        val ctx = try {
            nativeInit(modelPath)
        } catch (e: Throwable) {
            LogBus.log(TAG, "nativeInit 예외: ${e.message}")
            0L
        }
        if (ctx == 0L) {
            LogBus.log(TAG, "모델 로드 실패")
            return@withContext false
        }
        LogBus.log(TAG, "init 완료")
        try {
            val ret = nativeTranscribe(ctx, wavPath, language, threads, callback)
            LogBus.log(TAG, "transcribe 종료 ret=$ret")
            ret == 0
        } catch (e: Throwable) {
            LogBus.log(TAG, "transcribe 예외: ${e.message}")
            false
        } finally {
            nativeRelease(ctx)
            LogBus.log(TAG, "release 완료")
        }
    }
}
