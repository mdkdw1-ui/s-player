package com.mdkdw1.splayer.audio

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 네트워크 스트림을 백그라운드로 받아 메모리에 청크로 쌓는 버퍼.
 * MediaDataSource 가 이걸 읽어서 MediaExtractor 에 전달.
 */
class StreamingSource(
    private val url: String,
    private val maxBufferBytes: Long = 200L * 1024 * 1024
) {
    companion object { private const val TAG = "StreamingSource" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    data class Chunk(val start: Long, val data: ByteArray) {
        val end: Long get() = start + data.size
    }

    fun start() {
        if (downloadThread != null) return
        downloadThread = Thread {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "*/*")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        downloadError = "HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body ?: run {
                        downloadError = "empty body"
                        return@use
                    }
                    totalSize = body.contentLength()
                    Log.i(TAG, "시작 total=$totalSize")

                    val input: InputStream = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var offset = 0L

                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val copy = buf.copyOf(n)
                        bufferLock.lock()
                        try {
                            buffer.addLast(Chunk(offset, copy))
                            downloadedBytes = offset + n
                            // 버퍼 초과 시 오래된 것 삭제
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
                    Log.i(TAG, "다운로드 완료: $downloadedBytes bytes")
                }
            } catch (e: Exception) {
                downloadError = e.message ?: "unknown"
                Log.e(TAG, "다운로드 에러", e)
            } finally {
                downloadComplete = true
                bufferLock.lock()
                try { bufferCondition.signalAll() } finally { bufferLock.unlock() }
            }
        }.apply { isDaemon = true; start() }
    }

    fun readAt(position: Long, target: ByteArray, offset: Int, len: Int): Int {
        bufferLock.lock()
        try {
            var waited = 0L
            val maxWaitMs = 30_000L  // 최대 30초 대기
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
                } catch (e: InterruptedException) {
                    return -1
                }
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
