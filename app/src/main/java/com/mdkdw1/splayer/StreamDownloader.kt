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
    private const val MAX_RETRY = 5
    private const val CHUNK = 256 * 1024

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
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")

                if (downloaded > 0) {
                    reqBuilder.header("Range", "bytes=$downloaded-")
                    LogBus.log(TAG, "재개 시도 #${attempt + 1} ($downloaded bytes 부터)")
                } else {
                    LogBus.log(TAG, "다운로드 시작 #${attempt + 1}")
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        LogBus.log(TAG, "HTTP ${resp.code}")
                        return@withContext null
                    }

                    val body = resp.body ?: return@withContext null

                    // 전체 크기 (206 이면 Content-Range 에서, 아니면 Content-Length)
                    if (total < 0) {
                        total = if (resp.code == 206) {
                            val cr = resp.header("Content-Range") ?: ""
                            cr.substringAfterLast('/').toLongOrNull() ?: -1L
                        } else {
                            body.contentLength()
                        }
                        LogBus.log(TAG, "총 크기: $total bytes")
                    }

                    body.byteStream().use { input ->
                        RandomAccessFile(dest, "rw").use { raf ->
                            raf.seek(downloaded)
                            val buf = ByteArray(CHUNK)
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                raf.write(buf, 0, n)
                                downloaded += n

                                if (total > 0) {
                                    val p = downloaded.toFloat() / total
                                    // 1% 단위로만 콜백
                                    val pct = (p * 100).toInt()
                                    if (pct.toLong() != lastReport) {
                                        lastReport = pct.toLong()
                                        onProgress(p)
                                    }
                                }
                            }
                        }
                    }
                }

                // 완료 확인
                if (total > 0 && downloaded >= total) {
                    LogBus.log(TAG, "완료: ${dest.length()} bytes")
                    onProgress(1f)
                    return@withContext dest
                } else if (total < 0) {
                    // 크기 모를 때: 그냥 완료
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
                delay(1000L * attempt)  // 점진적 백오프
            }
        }

        LogBus.log(TAG, "최대 재시도 초과")
        null
    }
}
