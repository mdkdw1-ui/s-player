package com.mdkdw1.splayer

import android.content.Context
import com.mdkdw1.splayer.audio.AudioPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object StreamDownloader {

    private const val TAG = "StreamDownloader"
    private const val MAX_RETRY = 5
    private const val CHUNK = 64 * 1024

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

        val totalSize = probeSize(streamUrl)
        LogBus.log(TAG, "총 크기: $totalSize bytes")

        // 크기 미상 → 바로 단일
        if (totalSize <= 0) {
            LogBus.log(TAG, "크기 확인 실패 → 단일 다운로드")
            return@withContext downloadSingle(streamUrl, dest, onProgress)
        }

        val numParts = when {
            totalSize < 500_000 -> 1
            totalSize < 2_000_000 -> 2
            else -> 3
        }

        if (numParts == 1) {
            LogBus.log(TAG, "파일 작음 → 단일 다운로드")
            return@withContext downloadSingle(streamUrl, dest, onProgress)
        }

        // 병렬 시도
        LogBus.log(TAG, "★★★ 병렬 시도: $numParts 파트 ★★★")
        val parallelResult = tryParallel(streamUrl, dest, totalSize, numParts, onProgress)

        if (parallelResult != null) {
            LogBus.log(TAG, "병렬 성공: ${parallelResult.length()} bytes")
            return@withContext parallelResult
        }

        // 병렬 실패 → 단일 폴백
        LogBus.log(TAG, "★★★ 병렬 실패 → 단일 폴백 ★★★")
        if (dest.exists()) dest.delete()
        return@withContext downloadSingle(streamUrl, dest, onProgress)
    }

    // ==================== 병렬 ====================
    private fun tryParallel(
        url: String,
        dest: File,
        totalSize: Long,
        numParts: Int,
        onProgress: (Float) -> Unit
    ): File? {
        val startTime = System.currentTimeMillis()
        val partSize = (totalSize + numParts - 1) / numParts
        val partFiles = (0 until numParts).map { i ->
            File(dest.parentFile, "${dest.name}.part$i").apply { if (exists()) delete() }
        }
        val downloaded = LongArray(numParts) { 0L }
        val lock = Object()
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)

        val threads = (0 until numParts).map { i ->
            Thread {
                val start = i.toLong() * partSize
                val end = minOf(start + partSize - 1, totalSize - 1)
                val need = end - start + 1

                var attempt = 0
                while (attempt < MAX_RETRY && downloaded[i] < need && !failed.get()) {
                    try {
                        val rangeStart = start + downloaded[i]
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .header("Range", "bytes=$rangeStart-$end")
                            .header("Accept-Encoding", "identity")
                            .build()

                        client.newCall(req).execute().use { resp ->
                            if (resp.code != 206) {
                                // 206 아니면 병렬 불가
                                failed.set(true)
                                throw Exception("Range not supported (HTTP ${resp.code})")
                            }
                            val body = resp.body ?: throw Exception("no body")

                            body.byteStream().use { input ->
                                RandomAccessFile(partFiles[i], "rw").use { raf ->
                                    raf.seek(downloaded[i])
                                    val buf = ByteArray(CHUNK)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        raf.write(buf, 0, n)
                                        downloaded[i] += n

                                        val totalDone = synchronized(lock) { downloaded.sum() }
                                        onProgress(totalDone.toFloat() / totalSize)

                                        if (downloaded[i] >= need) break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (failed.get()) return@Thread
                        attempt++
                        LogBus.log(TAG, "파트 ${i+1} 재시도 $attempt: ${e.message}")
                        if (attempt < MAX_RETRY) {
                            try { Thread.sleep(800L * attempt) } catch (_: Exception) {}
                        }
                    }
                }

                if (downloaded[i] < need) {
                    failed.set(true)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        if (failed.get()) {
            partFiles.forEach { it.delete() }
            return null
        }

        // 합치기
        try {
            dest.outputStream().use { out ->
                for (i in 0 until numParts) {
                    partFiles[i].inputStream().use { it.copyTo(out) }
                    partFiles[i].delete()
                }
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "합치기 실패: ${e.message}")
            partFiles.forEach { it.delete() }
            return null
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val speed = if (elapsed > 0) dest.length() / 1024.0 / elapsed else 0.0
        LogBus.log(TAG, "병렬 완료: ${dest.length()} bytes (%.1f초, %.1fKB/s)".format(elapsed, speed))

        return if (dest.length() >= totalSize - 100) dest else null
    }

    // ==================== 단일 ====================
    private fun downloadSingle(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit
    ): File? {
        val startTime = System.currentTimeMillis()
        var downloaded = 0L
        var total = -1L
        var attempt = 0

        while (attempt < MAX_RETRY) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Encoding", "identity")

                if (downloaded > 0) reqBuilder.header("Range", "bytes=$downloaded-")

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val isPartial = resp.code == 206
                    if (!resp.isSuccessful && !isPartial) {
                        LogBus.log(TAG, "HTTP ${resp.code}")
                        return null
                    }
                    val body = resp.body ?: return null

                    if (total < 0) {
                        total = if (isPartial) {
                            resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                        } else body.contentLength()
                    }

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
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    val speed = if (elapsed > 0) dest.length() / 1024.0 / elapsed else 0.0
                    LogBus.log(TAG, "단일 완료: ${dest.length()} bytes (%.1f초, %.1fKB/s)".format(elapsed, speed))
                    onProgress(1f)
                    return dest
                } else if (total < 0) {
                    onProgress(1f)
                    return dest
                }
            } catch (e: Exception) {
                LogBus.log(TAG, "단일 재시도 #${attempt + 1}: ${e.message}")
            }

            attempt++
            if (attempt < MAX_RETRY) {
                try { Thread.sleep(800L * attempt) } catch (_: Exception) {}
            }
        }
        LogBus.log(TAG, "단일 최대 재시도 초과")
        return null
    }

    // ==================== 크기 확인 ====================
    private fun probeSize(url: String): Long {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-1")
                .build()
            client.newCall(req).execute().use { resp ->
                val cr = resp.header("Content-Range") ?: ""
                cr.substringAfterLast('/').toLongOrNull() ?: -1L
            }
        } catch (e: Exception) { -1L }
    }
}
