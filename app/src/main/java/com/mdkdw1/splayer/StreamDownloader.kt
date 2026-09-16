package com.mdkdw1.splayer

import android.content.Context
import com.mdkdw1.splayer.audio.AudioPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object StreamDownloader {

    private const val TAG = "StreamDownloader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        context: Context,
        streamUrl: String,
        tag: String,
        ext: String = "m4a",
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dest = AudioPaths.tempAudioInput(context, tag, ext)
            if (dest.exists()) dest.delete()

            val req = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var read = 0L

                body.byteStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
            }
            LogBus.log(TAG, "다운로드 완료: ${dest.length()} bytes")
            dest
        } catch (e: Exception) {
            LogBus.log(TAG, "실패: ${e.message}")
            null
        }
    }
}
