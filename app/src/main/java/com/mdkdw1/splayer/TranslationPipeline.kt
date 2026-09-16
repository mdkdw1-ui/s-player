package com.mdkdw1.splayer

import android.content.Context
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
    private val onResult: (original: String, translated: String, isFinal: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val vosk = VoskStt(appContext, language)
    private val translator = GoogleTranslator()
    private val mutex = Mutex()

    @Volatile private var ready = false
    private var sourceRate = 48000
    private val targetRate = 16000
    private var chunkCount = 0L
    private var lastLogAt = 0L

    // partial 중복 콜백 방지
    private var lastPartial = ""

    init {
        scope.launch {
            ready = vosk.initialize()
            LogBus.log("PIPE", "vosk ready=$ready lang=${language.code}")
        }
    }

    fun push(samples: FloatArray, sampleRate: Int = 48000) {
        if (!ready) {
            if (chunkCount == 0L) LogBus.log("PIPE", "drop chunk (not ready)")
            chunkCount++
            return
        }
        sourceRate = sampleRate
        chunkCount++

        val now = System.currentTimeMillis()
        if (now - lastLogAt > 3000) {
            lastLogAt = now
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = kotlin.math.sqrt(sum / samples.size)
            LogBus.log("PIPE", "chunks=$chunkCount rms=%.4f".format(rms))
        }

        val pcm16 = floatToPcm16Resampled(samples, sourceRate, targetRate)

        scope.launch {
            mutex.withLock {
                try {
                    val res = vosk.acceptWaveform(pcm16) ?: return@withLock

                    if (!res.isFinal) {
                        // partial: 원문만 즉시 표시, 번역은 안 함
                        if (res.text != lastPartial) {
                            lastPartial = res.text
                            onResult(res.text, "", isFinal = false)
                        }
                    } else {
                        // final: 번역 수행
                        lastPartial = ""
                        val text = res.text
                        LogBus.log("STT", "final=${text.take(60)}")

                        val translated = if (language.code == "ko") ""
                        else translator.translate(text, target = "ko", source = language.code)

                        if (translated.isNotBlank()) {
                            LogBus.log("TRANS", translated.take(60))
                        } else if (language.code != "ko") {
                            LogBus.log("TRANS", "번역 실패/빈결과")
                        }
                        onResult(text, translated, isFinal = true)
                    }
                } catch (e: Exception) {
                    LogBus.log("PIPE", "accept 오류 ${e.message}")
                }
            }
        }
    }

    fun reset() {
        scope.launch {
            mutex.withLock {
                try { vosk.finish() } catch (_: Exception) {}
                lastPartial = ""
            }
        }
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
}
