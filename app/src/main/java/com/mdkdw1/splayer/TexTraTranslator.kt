package com.mdkdw1.splayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object TexTraTranslator {

    private const val TAG = "TexTra"
    private const val TOKEN_URL = "https://mt-auto-minhon-mlt.ucri.jgn-x.jp/oauth2/token.php"
    private const val API_URL = "https://mt-auto-minhon-mlt.ucri.jgn-x.jp/api/"

    // 로그인 ID (NICT 사이트의 UserID)
    private const val USER_NAME = "kjf6k"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val lock = ReentrantLock()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { if (now < tokenExpiresAt) return it }

        lock.lock()
        try {
            cachedToken?.let { if (now < tokenExpiresAt) return it }

            val cid = BuildConfig.TEXTA_CLIENT_ID
            val csec = BuildConfig.TEXTA_CLIENT_SECRET
            if (cid.isBlank() || csec.isBlank()) {
                LogBus.log(TAG, "키 없음")
                return null
            }

            val body = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", cid)
                .add("client_secret", csec)
                .build()

            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "토큰 HTTP ${resp.code}")
                    return null
                }
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (token.isBlank()) return null
                cachedToken = token
                tokenExpiresAt = now + expiresIn - 60
                LogBus.log(TAG, "토큰 발급 OK")
                return token
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "토큰 예외: ${e.message}")
            return null
        } finally {
            lock.unlock()
        }
    }

    suspend fun translate(
        text: String,
        target: String = "ko",
        source: String = "ja"
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        val token = getAccessToken() ?: return@withContext null
        val s = if (source == "auto") "ja" else source
        val apiParam = "generalNT_${s}_${target}"

        try {
            val body = FormBody.Builder()
                .add("access_token", token)
                .add("key", BuildConfig.TEXTA_CLIENT_ID)
                .add("api_name", "mt")
                .add("api_param", apiParam)
                .add("name", USER_NAME)
                .add("type", "json")
                .add("text", text)
                .build()

            val req = Request.Builder().url(API_URL).post(body).build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "HTTP ${resp.code}")
                    return@withContext null
                }

                val json = JSONObject(bodyStr)
                val rs = json.optJSONObject("resultset") ?: return@withContext null
                val code = rs.optInt("code", -1)

                // 성공은 0
                if (code != 0) {
                    LogBus.log(TAG, "code=$code (실패)")
                    return@withContext null
                }

                val result = rs.optJSONObject("result")
                val translated = result?.optString("text", "") ?: ""
                if (translated.isBlank()) {
                    LogBus.log(TAG, "빈 결과")
                    return@withContext null
                }
                LogBus.log(TAG, "번역 OK (${translated.length}자)")
                translated
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "번역 예외: ${e.message}")
            null
        }
    }

    fun isConfigured(): Boolean =
        BuildConfig.TEXTA_CLIENT_ID.isNotBlank() &&
        BuildConfig.TEXTA_CLIENT_SECRET.isNotBlank()
}
