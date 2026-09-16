package com.mdkdw1.splayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TranslationPipeline(
    context: Context,
    private val language: SttLanguage,
    private val onResult: (original: String, translated: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val vosk = VoskStt(appContext, language)
    private val translator = GoogleTranslator()
    private val mutex = Mutex()

    @Volatile private var ready = false
    private var sourceRate = 48000
    private val targetRate = 16000

    init {
        scope.launch {
            ready = vosk.initialize()
            Log.i(TAG, "Vosk ready=$ready lang=${language.code}")
        }
    }

    fun push(samples: FloatArray, sampleRate: Int = 48000) {
        if (!ready) return
        sourceRate = sampleRate
        val pcm16 = floatToPcm16Resampled(samples, sourceRate, targetRate)

        scope.launch {
            mutex.withLock {
                try {
                    val text = vosk.acceptWaveform(pcm16)
                    if (text.isNotBlank()) {
                        // 원본이 한국어면 번역 생략, 그 외에는 무조건 한국어로
                        val translated = if (language.code == "ko") {
                            ""   // 원본 그대로 표시
                        } else {
                            translator.translate(
                                text = text,
                                target = "ko",
                                source = language.code   // en, ja 등 명시
                            )
                        }
                        onResult(text, translated)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "accept 오류", e)
                }
            }
        }
    }

    fun reset() {
        scope.launch { mutex.withLock { try { vosk.finish() } catch (_: Exception) {} } }
    }

    fun close() {
        try { vosk.close() } catch (_: Exception) {}
    }

    private fun floatToPcm16Resampled(input: FloatArray, from: Int, to: Int): ByteArray {
        val resampled: FloatArray = if (from == to) input else {
            val ratio = to.toDouble() / from.toDouble()
            val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val srcIdx = i / ratio
                val i0 = srcIdx.toInt().coerceIn(0, input.size - 1)
                val i1 = (i0 + 1).coerceAtMost(input.size - 1)
                val frac = (srcIdx - i0).toFloat()
                out[i] = input[i0] * (1 - frac) + input[i1] * frac
            }
            out
        }
        val buf = ByteBuffer.allocate(resampled.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in resampled) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buf.putShort(s)
        }
        return buf.array()
    }

    companion object {
        private const val TAG = "TranslationPipeline"
    }
}
