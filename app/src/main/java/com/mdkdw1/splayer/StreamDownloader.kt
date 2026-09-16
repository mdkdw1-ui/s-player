package com.mdkdw1.splayer

import android.content.Context
import com.mdkdw1.splayer.audio.AudioPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object StreamDownloader {

    private const val TAG = "StreamDownloader"
    private const val MAX_RETRY = 10
    private const val CHUNK = 512 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(
        context: Context,
        streamUrl: String,
        tag: String,
        ext: String = "m4a",
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dest = AudioPaths.tempAudioInput(context, tag, ext)
        if (dest.exists()) dest.delete()

        var attempt = 0
        var downloaded = 0L
        var total = -1L

        while (attempt < MAX_RETRY) {
            try {
                val reqBuilder = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")

                if (downloaded > 0) {
                    reqBuilder.header("Range", "bytes=$downloaded-")
                    LogBus.log(TAG, "이어받기 #${attempt + 1} @ $downloaded")
                } else {
                    LogBus.log(TAG, "다운로드 시작 #${attempt + 1}")
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val isPartial = resp.code == 206
                    if (!resp.isSuccessful && !isPartial) {
                        LogBus.log(TAG, "HTTP ${resp.code}")
                        return@withContext null
                    }

                    val body = resp.body ?: return@withContext null

                    if (total < 0) {
                        total = if (isPartial) {
                            val cr = resp.header("Content-Range") ?: ""
                            cr.substringAfterLast('/').toLongOrNull() ?: -1L
                        } else {
                            body.contentLength()
                        }
                        LogBus.log(TAG, "총 크기: $total bytes")
                    }

                    // 이어받기: 새 응답이 206이면 seek, 아니면 0부터
                    val startPos = if (isPartial) downloaded else 0L
                    if (!isPartial) downloaded = 0L

                    body.byteStream().use { input ->
                        RandomAccessFile(dest, "rw").use { raf ->
                            raf.seek(startPos)
                            val buf = ByteArray(CHUNK)
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                raf.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val pct = (downloaded * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(downloaded.toFloat() / total)
                                    }
                                }
                            }
                        }
                    }
                }

                if (total > 0 && downloaded >= total) {
                    LogBus.log(TAG, "완료: ${dest.length()} bytes")
                    onProgress(1f)
                    return@withContext dest
                } else if (total < 0) {
                    LogBus.log(TAG, "완료 (크기 미상): ${dest.length()} bytes")
                    onProgress(1f)
                    return@withContext dest
                } else {
                    LogBus.log(TAG, "불완전 ($downloaded / $total), 재시도")
                }
            } catch (e: Exception) {
                LogBus.log(TAG, "예외 #${attempt + 1}: ${e.message}")
            }

            attempt++
            if (attempt < MAX_RETRY) {
                delay(1000L * attempt)
            }
        }
        LogBus.log(TAG, "최대 재시도 초과")
        null
    }
}
