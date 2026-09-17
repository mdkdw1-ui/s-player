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
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // dict-chrome-ex 가 gtx 보다 차단이 덜함 (2024년 기준)
    private val CLIENT = "dict-chrome-ex"

    suspend fun translate(text: String, target: String = "ko", source: String = "auto"): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext ""

            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=$CLIENT&sl=$source&tl=$target&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        LogBus.log(TAG, "HTTP ${resp.code}")
                        return@withContext ""
                    }

                    // HTML 응답이면 차단됨
                    if (body.startsWith("<")) {
                        LogBus.log(TAG, "차단됨 (HTML 응답)")
                        return@withContext ""
                    }

                    // JSON 파싱
                    try {
                        val root = JSONArray(body)
                        val arr = root.getJSONArray(0)
                        val sb = StringBuilder()
                        for (i in 0 until arr.length()) {
                            val seg = arr.getJSONArray(i)
                            val piece = seg.optString(0, "")
                            sb.append(piece)
                        }
                        sb.toString()
                    } catch (e: Exception) {
                        LogBus.log(TAG, "파싱 실패: ${body.take(100)}")
                        ""
                    }
                }
            } catch (e: Exception) {
                LogBus.log(TAG, "예외: ${e.message}")
                ""
            }
        }

    companion object {
        private const val TAG = "GoogleTranslator"
    }
}
