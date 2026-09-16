package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GoogleTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun translate(text: String, target: String = "ko", source: String = "auto"): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext ""

            // 비공식 무료 엔드포인트 (2024년 기준 동작)
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=$source&tl=$target&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@withContext ""
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "translate 실패 ${resp.code}")
                        return@withContext ""
                    }
                    // 응답: [[["번역문","원문",...],...],...]
                    val root = JSONArray(body)
                    val arr = root.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val seg = arr.getJSONArray(i)
                        sb.append(seg.optString(0, ""))
                    }
                    sb.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "translate 예외", e)
                ""
            }
        }

    companion object {
        private const val TAG = "GoogleTranslator"
    }
}
