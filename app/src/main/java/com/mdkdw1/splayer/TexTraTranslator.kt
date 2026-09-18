package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * NICT みんなの自動翻訳@TexTra API.
 * - OAuth2.0 (client_credentials)
 * - 토큰은 앱 내에서 캐시 (1시간 유효)
 * - 일본어 ↔ 한국어/영어/중국어 우수
 *
 * 주의: Termux 서버 없이 앱에서 직접 호출. 개인 비상업 사용 한정.
 */
object TexTraTranslator {

    private const val TAG = "TexTra"
    private const val TOKEN_URL = "https://mt-auto-minhon-mlt.ucri.jgn-x.jp/oauth2/token.php"
    private const val BASE_URL = "https://mt-auto-minhon-mlt.ucri.jgn-x.jp/api/mt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val lock = ReentrantLock()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    /**
     * 언어 코드 매핑. TexTra 는 4자리 ISO (ja, en, zh, ko, ...).
     */
    private fun endpointFor(source: String, target: String): String {
        val s = if (source == "auto") "ja" else source
        // 예: generalNT_ja_ko
        return "$BASE_URL/generalNT_${s}_${target}/"
    }

    /**
     * OAuth2 토큰 발급 (캐시).
     */
    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { if (now < tokenExpiresAt) return it }

        lock.lock()
        try {
            // 이중 확인
            cachedToken?.let { if (now < tokenExpiresAt) return it }

            val cid = BuildConfig.TEXTA_CLIENT_ID
            val csec = BuildConfig.TEXTA_CLIENT_SECRET
            if (cid.isBlank() || csec.isBlank()) {
                LogBus.log(TAG, "키 없음 (TEXTA_CLIENT_ID/SECRET)")
                return null
            }

            LogBus.log(TAG, "토큰 발급 요청")
            val body = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", cid)
                .add("client_secret", csec)
                .build()

            val req = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "토큰 HTTP ${resp.code}: ${bodyStr.take(200)}")
                    return null
                }
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (token.isBlank()) {
                    LogBus.log(TAG, "토큰 없음: ${bodyStr.take(200)}")
                    return null
                }
                cachedToken = token
                tokenExpiresAt = now + expiresIn - 60  // 1분 여유
                LogBus.log(TAG, "토큰 발급 완료 (만료까지 ${expiresIn}s)")
                return token
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "토큰 발급 예외: ${e.message}")
            return null
        } finally {
            lock.unlock()
        }
    }

    /**
     * 번역 실행. 실패 시 null 반환 (호출자가 폴백).
     */
    suspend fun translate(
        text: String,
        target: String = "ko",
        source: String = "ja"
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        val token = getAccessToken() ?: return@withContext null
        val endpoint = endpointFor(source, target)

        try {
            val body = FormBody.Builder()
                .add("text", text)
                .add("type", "json")
                .build()

            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "번역 HTTP ${resp.code}: ${bodyStr.take(200)}")
                    return@withContext null
                }

                // 응답 형식: {"resultSet": {"result": {"text": "..."}}}
                val json = JSONObject(bodyStr)
                val resultSet = json.optJSONObject("resultSet")
                val result = resultSet?.optJSONObject("result")
                val translated = result?.optString("text", "")
                    ?: resultSet?.optString("text", "")
                    ?: ""

                if (translated.isBlank()) {
                    LogBus.log(TAG, "빈 결과: ${bodyStr.take(200)}")
                    return@withContext null
                }
                LogBus.log(TAG, "번역 성공 (${translated.length}자)")
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
