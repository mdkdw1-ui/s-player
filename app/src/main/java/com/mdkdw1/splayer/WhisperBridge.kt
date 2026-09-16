package com.mdkdw1.splayer

object WhisperBridge {
    init {
        System.loadLibrary("splayer_whisper")
    }

    external fun nativeVersion(): String
    external fun nativeInit(modelPath: String): Boolean
}
