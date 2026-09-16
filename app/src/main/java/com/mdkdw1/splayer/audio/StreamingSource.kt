package com.mdkdw1.splayer.audio

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class StreamingSource(
    private val url: String,
    private val maxBufferBytes: Long = 200L * 1024 * 1024
) {
    companion object {
        private const val TAG = "StreamingSource"
        private const val MAX_RETRY = 8
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val buffer = ArrayDeque<Chunk>()
    private val bufferLock = ReentrantLock()
    private val bufferCondition = bufferLock.newCondition()

    @Volatile var totalSize: Long = -1L
        private set

    @Volatile var downloadComplete: Boolean = false
        private set

    @Volatile var downloadError: String? = null
        private set

    @Volatile var downloadedBytes: Long = 0L
        private set

    private var downloadThread: Thread? = null
    private var discardedBytes: Long = 0L

    // 이어받기용: 지금까지 버퍼에 남아있는 최대 위치
    private var lastBufferedEnd: Long = 0L

    data class Chunk(val start: Long, val data: ByteArray) {
        val end: Long get() = start + data.size
    }

    fun start() {
        if (downloadThread != null) return
        downloadThread = Thread {
            var attempt = 0
            while (attempt < MAX_RETRY && !downloadComplete) {
                try {
                    downloadOnce(attempt)
                    // downloadOnce 가 정상 종료되면 완료
                    break
                } catch (e: Exception) {
                    downloadError = e.message ?: "unknown"
                    Log.e(TAG, "다운로드 예외 #${attempt + 1}: ${e.message}")
                    LogBus.log(TAG, "재시도 #${attempt + 1}: ${e.message}")
                    attempt++
                    if (attempt < MAX_RETRY) {
                        try { Thread.sleep(1000L * attempt) } catch (_: Exception) {}
                    }
                }
            }
            if (attempt >= MAX_RETRY) {
                downloadError = "최대 재시도 초과"
            }
            downloadComplete = true
            bufferLock.lock()
            try { bufferCondition.signalAll() } finally { bufferLock.unlock() }
        }.apply { isDaemon = true; start() }
    }

    private fun downloadOnce(attempt: Int) {
        // 이어받기 시작 위치
        val startPos = lastBufferedEnd

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")

        if (startPos > 0) {
            reqBuilder.header("Range", "bytes=$startPos-")
            Log.i(TAG, "이어받기 #${attempt + 1} @ $startPos")
        } else {
            Log.i(TAG, "새 다운로드 #${attempt + 1}")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val isPartial = resp.code == 206
            if (!resp.isSuccessful && !isPartial) {
                throw Exception("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw Exception("empty body")

            // 전체 크기 (206 이면 Content-Range 에서)
            if (totalSize < 0) {
                totalSize = if (isPartial) {
                    val cr = resp.header("Content-Range") ?: ""
                    cr.substringAfterLast('/').toLongOrNull() ?: -1L
                } else {
                    body.contentLength()
                }
                Log.i(TAG, "totalSize=$totalSize")
            }

            val input: InputStream = body.byteStream()
            val buf = ByteArray(64 * 1024)
            var offset = if (isPartial) startPos else 0L

            // 새로 시작이면 버퍼 초기화
            if (!isPartial && startPos == 0L) {
                bufferLock.lock()
                try {
                    buffer.clear()
                    downloadedBytes = 0
                } finally { bufferLock.unlock() }
            }

            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val copy = buf.copyOf(n)
                bufferLock.lock()
                try {
                    buffer.addLast(Chunk(offset, copy))
                    downloadedBytes = offset + n
                    lastBufferedEnd = downloadedBytes

                    var total = 0L
                    for (c in buffer) total += c.data.size
                    while (buffer.isNotEmpty() && total > maxBufferBytes) {
                        val removed = buffer.removeFirst()
                        total -= removed.data.size
                        discardedBytes += removed.data.size
                    }
                    bufferCondition.signalAll()
                } finally {
                    bufferLock.unlock()
                }
                offset += n
            }

            if (totalSize > 0 && downloadedBytes >= totalSize) {
                Log.i(TAG, "다운로드 완료: $downloadedBytes / $totalSize")
                downloadComplete = true
            } else {
                // 조기 종료 → 재시도 유도
                Log.i(TAG, "조기 종료: $downloadedBytes / $totalSize")
                if (totalSize > 0 && downloadedBytes < totalSize) {
                    throw Exception("early EOF")
                }
            }
        }
    }

    fun readAt(position: Long, target: ByteArray, offset: Int, len: Int): Int {
        bufferLock.lock()
        try {
            var waited = 0L
            val maxWaitMs = 30_000L
            while (true) {
                val chunk = findChunk(position)
                if (chunk != null) {
                    val chunkOffset = (position - chunk.start).toInt()
                    val available = chunk.data.size - chunkOffset
                    val toCopy = minOf(len, available)
                    System.arraycopy(chunk.data, chunkOffset, target, offset, toCopy)
                    return toCopy
                }
                if (downloadComplete) {
                    return if (position >= downloadedBytes) -1 else 0
                }
                try {
                    bufferCondition.await(1000, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) { return -1 }
                waited += 1000
                if (waited > maxWaitMs) {
                    Log.e(TAG, "readAt 타임아웃 pos=$position")
                    return -1
                }
            }
        } finally {
            bufferLock.unlock()
        }
    }

    private fun findChunk(position: Long): Chunk? {
        for (c in buffer) {
            if (position >= c.start && position < c.end) return c
        }
        return null
    }

    fun close() {
        downloadThread?.interrupt()
        downloadThread = null
        bufferLock.lock()
        try { buffer.clear() } finally { bufferLock.unlock() }
    }
}
