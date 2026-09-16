package com.mdkdw1.splayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object WhisperModelDownloader {
    private const val TAG = "WhisperModelDL"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    fun modelFile(context: Context, model: WhisperModel): File =
        File(context.filesDir, "models/ggml-${model.id}.bin")

    fun isInstalled(context: Context, model: WhisperModel): Boolean {
        val f = modelFile(context, model)
        return f.exists() && f.length() > 1_000_000  // 최소 1MB 이상
    }

    /**
     * 모델 다운로드. 이어받기 지원.
     * @param onProgress 0.0 ~ 1.0
     */
    suspend fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = modelFile(context, model)
            target.parentFile?.mkdirs()

            var downloaded = if (target.exists()) target.length() else 0L

            val req = Request.Builder()
                .url(model.url)
                .header("User-Agent", "SPlayer/1.0")
                .apply {
                    if (downloaded > 0) addHeader("Range", "bytes=$downloaded-")
                }
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    Log.e(TAG, "HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val contentLen = body.contentLength()
                val total = if (contentLen > 0) downloaded + contentLen else -1L

                body.byteStream().use { input ->
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(downloaded)
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
            }

            Log.i(TAG, "다운로드 완료: ${target.absolutePath}, ${target.length()} bytes")
            onProgress(1f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "다운로드 실패", e)
            false
        }
    }

    suspend fun delete(context: Context, model: WhisperModel): Boolean =
        withContext(Dispatchers.IO) {
            modelFile(context, model).delete()
        }
}
