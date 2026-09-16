package com.mdkdw1.splayer

enum class WhisperModel(
    val id: String,
    val displayName: String,
    val url: String,
    val sizeMb: Int,
    val description: String
) {
    TINY(
        id = "tiny",
        displayName = "Tiny",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        sizeMb = 75,
        description = "가장 빠름, 정확도 낮음"
    ),
    BASE(
        id = "base",
        displayName = "Base",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        sizeMb = 142,
        description = "권장, 균형"
    ),
    SMALL(
        id = "small",
        displayName = "Small",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        sizeMb = 466,
        description = "정확도 높음, 느림"
    ),
    MEDIUM(
        id = "medium",
        displayName = "Medium",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
        sizeMb = 1500,
        description = "매우 정확, 매우 느림"
    )
}
