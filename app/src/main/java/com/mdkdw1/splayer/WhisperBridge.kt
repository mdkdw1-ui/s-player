package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WhisperBridge {
    private const val TAG = "WhisperBridge"

    init {
        System.loadLibrary("splayer_whisper")
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
        nativeSystemInfo()
    } catch (e: Throwable) {
        Log.e(TAG, "systemInfo 실패", e); "unknown"
    }

    /**
     * 모델 로드 + 트랜스크립션 + 자동 해제.
     * 세그먼트는 onSegment 로 스트리밍, 완료 시 onComplete.
     */
    suspend fun transcribe(
        modelPath: String,
        wavPath: String,
        language: String = "auto",
        threads: Int = 4,
        callback: SegmentCallback
    ): Boolean = withContext(Dispatchers.Default) {
        val ctx = nativeInit(modelPath)
        if (ctx == 0L) {
            Log.e(TAG, "모델 로드 실패: $modelPath")
            return@withContext false
        }
        try {
            val ret = nativeTranscribe(ctx, wavPath, language, threads, callback)
            ret == 0
        } finally {
            nativeRelease(ctx)
        }
    }
}
