package com.mdkdw1.splayer

import android.content.Context

/**
 * 실시간 오디오 캡처용 파이프라인 (Vosk 제거됨).
 * 지금은 스텁. 나중에 whisper.cpp 스트리밍이나 다른 STT로 구현.
 */
class TranslationPipeline(
    context: Context,
    private val language: SttLanguage,
    private val onResult: (original: String, translated: String, isFinal: Boolean) -> Unit
) {
    fun push(samples: FloatArray, sampleRate: Int = 48000) {
        // TODO: 실시간 STT 나중에 구현
    }

    fun reset() {}

    fun close() {}
}
