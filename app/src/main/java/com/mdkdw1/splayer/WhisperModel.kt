package com.mdkdw1.splayer

enum class WhisperModel(
    val id: String,
    val displayName: String,
    val url: String,
    val sizeMb: Int,
    val description: String,
    val recommended: Boolean = false,
    val isCloud: Boolean = false
) {
    GROQ(
        id = "groq",
        displayName = "Groq Large-v3",
        url = "",
        sizeMb = 0,
        description = "☁️ 클라우드, 최고 정확, 20배 빠름 ⭐",
        recommended = true,
        isCloud = true
    ),
    TINY(
        id = "tiny",
        displayName = "Tiny (로컬)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        sizeMb = 75,
        description = "오프라인, 빠름, 정확도 낮음"
    ),
    BASE(
        id = "base",
        displayName = "Base (로컬)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        sizeMb = 142,
        description = "오프라인, 균형"
    ),
    SMALL(
        id = "small",
        displayName = "Small (로컬)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        sizeMb = 466,
        description = "오프라인, 정확, 느림"
    ),
    MEDIUM(
        id = "medium",
        displayName = "Medium (로컬)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
        sizeMb = 1500,
        description = "오프라인, 매우 정확, 매우 느림"
    )
}
