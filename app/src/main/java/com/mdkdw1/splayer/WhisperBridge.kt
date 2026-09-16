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

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    interface SegmentCallback {
        fun onSegment(startMs: Long, endMs: Long, text: String)
        fun onProgress(percent: Int)
        fun onComplete()
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

    fun systemInfo(): String = try {
        nativeSystemInfo().also { LogBus.log(TAG, "sys: $it") }
    } catch (e: Throwable) {
        Log.e(TAG, "systemInfo 실패", e); "unknown"
    }

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
        LogBus.log(TAG, "init 완료 ctx=$ctx")

        try {
            LogBus.log(TAG, "transcribe 시작")
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
