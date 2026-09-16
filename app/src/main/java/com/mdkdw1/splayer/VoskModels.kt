package com.mdkdw1.splayer

enum class SttLanguage(
    val code: String,
    val displayName: String,
    val modelUrl: String,
    val modelDirName: String,
    val approxMb: Int
) {
    KOREAN(
        code = "ko",
        displayName = "한국어",
        modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip",
        modelDirName = "vosk-model-small-ko-0.22",
        approxMb = 83
    ),
    ENGLISH(
        code = "en",
        displayName = "English",
        modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        modelDirName = "vosk-model-small-en-us-0.15",
        approxMb = 40
    ),
    JAPANESE(
        code = "ja",
        displayName = "日本語",
        modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
        modelDirName = "vosk-model-small-ja-0.22",
        approxMb = 48
    )
}
