package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Groq Whisper API (OpenAI 호환).
 * - whisper-large-v3: 최고 정확도, 한국어/일본어/중국어 모두 우수
 * - whisper-large-v3-turbo: 더 빠름
 * - 60초 오디오 → 약 10초 처리
 */
object GroqStt {

    private const val TAG = "GroqStt"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3"

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    data class Result(
        val text: String,
        val language: String?,
        val segments: List<Segment>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * @param wavFile 16kHz mono WAV
     * @param language "auto" or "ja", "en", "ko"...
     * @param onProgress 0~100
     */
    suspend fun transcribe(
        wavFile: File,
        language: String = "auto",
        onProgress: (Int) -> Unit = {}
    ): Result? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            LogBus.log(TAG, "API 키 없음")
            return@withContext null
        }

        try {
            onProgress(5)
            LogBus.log(TAG, "업로드 시작: ${wavFile.length() / 1024}KB")

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")

            if (language != "auto") {
                bodyBuilder.addFormDataPart("language", language)
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            onProgress(15)

            client.newCall(request).execute().use { resp ->
                onProgress(80)
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "HTTP ${resp.code}: ${bodyStr.take(200)}")
                    return@withContext null
                }

                val json = JSONObject(bodyStr)
                val text = json.optString("text", "")
                val detectedLang: String? = if (json.has("language") && !json.isNull("language")) {
                    json.getString("language")
                } else null

                // 세그먼트 파싱
                val segments = mutableListOf<Segment>()
                val segsArr = json.optJSONArray("segments")
                if (segsArr != null) {
                    for (i in 0 until segsArr.length()) {
                        val s = segsArr.getJSONObject(i)
                        val start = (s.optDouble("start", 0.0) * 1000).toLong()
                        val end = (s.optDouble("end", 0.0) * 1000).toLong()
                        val t = s.optString("text", "").trim()
                        if (t.isNotEmpty()) {
                            segments.add(Segment(start, end, t))
                        }
                    }
                } else if (text.isNotBlank()) {
                    // 세그먼트 없으면 전체를 하나로
                    segments.add(Segment(0, 0, text))
                }

                onProgress(100)
                LogBus.log(TAG, "완료: ${segments.size} 세그먼트, 언어=$detectedLang")
                Result(text = text, language = detectedLang, segments = segments)
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "예외: ${e.message}")
            Log.e(TAG, "Groq 오류", e)
            null
        }
    }
}
