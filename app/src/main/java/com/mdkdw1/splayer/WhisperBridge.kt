package com.mdkdw1.splayer

import android.util.Log

object WhisperBridge {
    private const val TAG = "WhisperBridge"

    init {
        System.loadLibrary("splayer_whisper")
    }

    external fun nativeSystemInfo(): String
    external fun nativeInit(modelPath: String): Long

    fun systemInfo(): String = try {
        nativeSystemInfo()
    } catch (e: Throwable) {
        Log.e(TAG, "systemInfo 실패", e)
        "unknown"
    }
}
