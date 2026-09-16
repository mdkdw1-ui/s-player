package com.mdkdw1.splayer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskStt(
    private val context: Context,
    private val language: SttLanguage,
    private val sampleRate: Float = 16000f
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    data class Result(
        val text: String,
        val isFinal: Boolean
    )

    fun initialize(): Boolean {
        if (model != null) return true
        return try {
            val dir = ModelDownloader.modelDir(context, language)
            if (!dir.exists()) {
                Log.w(TAG, "모델 없음: ${dir.absolutePath}")
                return false
            }
            model = Model(dir.absolutePath)
            recognizer = Recognizer(model, sampleRate)
            Log.i(TAG, "Vosk 준비: ${language.code}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk 초기화 실패", e)
            false
        }
    }

    fun acceptWaveform(pcm16: ByteArray): Result? {
        val rec = recognizer ?: return null
        return try {
            if (rec.acceptWaveForm(pcm16, pcm16.size)) {
                val text = JSONObject(rec.result).optString("text", "").trim()
                if (text.isBlank()) null else Result(text, isFinal = true)
            } else {
                val partial = JSONObject(rec.partialResult).optString("partial", "").trim()
                if (partial.isBlank()) null else Result(partial, isFinal = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "accept 예외", e)
            null
        }
    }

    fun finish(): String {
        val rec = recognizer ?: return ""
        return JSONObject(rec.finalResult).optString("text", "").trim()
    }

    fun close() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
    }

    companion object {
        private const val TAG = "VoskStt"
    }
}
