package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GroqStt {

    private const val TAG = "GroqStt"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3"
    private const val MAX_CHUNK_BYTES = 20 * 1024 * 1024
    private const val CONCURRENCY = 3

    data class Segment(val startMs: Long, val endMs: Long, val text: String)
    data class Result(val text: String, val language: String?, val segments: List<Segment>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(
        wavFile: File,
        language: String = "auto",
        onProgress: (Int) -> Unit = {}
    ): Result? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            LogBus.log(TAG, "API 키 없음")
            return@withContext null
        }

        // 단일 파일
        if (wavFile.length() <= MAX_CHUNK_BYTES) {
            LogBus.log(TAG, "단일 파일 (${wavFile.length() / 1024}KB)")
            return@withContext transcribeSingle(wavFile, language, 0L, onProgress)
        }

        // 청크 병렬
        LogBus.log(TAG, "★★★ Groq 청크 병렬 모드 ★★★")
        LogBus.log(TAG, "   파일: ${wavFile.length() / 1024 / 1024}MB")

        val chunks = splitWav(wavFile, MAX_CHUNK_BYTES)
        if (chunks.isEmpty()) return@withContext null

        val total = chunks.size
        LogBus.log(TAG, "   청크 ${total}개, 동시 $CONCURRENCY 처리")

        val results = arrayOfNulls<Result>(total)
        val semaphore = Semaphore(CONCURRENCY)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val startTime = System.currentTimeMillis()

        try {
            coroutineScope {
                chunks.forEachIndexed { i, (chunkFile, offsetMs) ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val chunkMb = chunkFile.length() / 1024 / 1024
                            LogBus.log(TAG, "  [청크 ${i+1}/$total] 시작 (offset=${offsetMs}ms, ${chunkMb}MB)")

                            var result: Result? = null
                            var attempt = 0
                            while (attempt < 3 && result == null) {
                                try {
                                    result = transcribeSingle(chunkFile, language, offsetMs) {}
                                    if (result == null) {
                                        attempt++
                                        if (attempt < 3) delay(2000L * attempt)
                                    }
                                } catch (e: Exception) {
                                    attempt++
                                    if (attempt < 3) delay(2000L * attempt)
                                }
                            }

                            chunkFile.delete()
                            results[i] = result
                            val done = doneCount.incrementAndGet()
                            val elapsed = System.currentTimeMillis() - startTime
                            val eta = if (done > 0) (elapsed * (total - done) / done) / 1000 else 0

                            onProgress(done * 100 / total)
                            LogBus.log(TAG, "  [청크 $done/$total] 완료 (성공=${result != null}, 남은 ${eta}초)")
                            delay(300)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "병렬 예외: ${e.message}")
        }

        val allSegments = mutableListOf<Segment>()
        var detectedLang: String? = null
        val fullText = StringBuilder()
        var success = 0

        results.forEachIndexed { i, r ->
            if (r != null) {
                success++
                allSegments.addAll(r.segments)
                if (detectedLang == null) detectedLang = r.language
                fullText.append(r.text).append(" ")
            } else {
                LogBus.log(TAG, "  청크 ${i+1} 최종 실패")
            }
        }

        val elapsedTotal = (System.currentTimeMillis() - startTime) / 1000.0
        LogBus.log(TAG, "★★★ Groq 완료: $success/$total 청크 (%.1f초) ★★★".format(elapsedTotal))
        onProgress(100)

        if (allSegments.isEmpty()) return@withContext null

        Result(fullText.toString().trim(), detectedLang, allSegments)
    }

    private suspend fun transcribeSingle(
        wavFile: File,
        language: String,
        offsetMs: Long,
        onProgress: (Int) -> Unit
    ): Result? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        try {
            onProgress(5)
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", wavFile.name, wavFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("temperature", "0")

            if (language != "auto") bodyBuilder.addFormDataPart("language", language)

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            onProgress(20)
            client.newCall(request).execute().use { resp ->
                onProgress(80)
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "HTTP ${resp.code}: ${bodyStr.take(200)}")
                    return@withContext null
                }

                val json = JSONObject(bodyStr)
                val text = json.optString("text", "")
                val detectedLang: String? = if (json.has("language") && !json.isNull("language"))
                    json.getString("language") else null

                val segments = mutableListOf<Segment>()
                val segsArr = json.optJSONArray("segments")
                if (segsArr != null) {
                    for (i in 0 until segsArr.length()) {
                        val s = segsArr.getJSONObject(i)
                        val start = (s.optDouble("start", 0.0) * 1000).toLong() + offsetMs
                        val end = (s.optDouble("end", 0.0) * 1000).toLong() + offsetMs
                        val t = s.optString("text", "").trim()
                        if (t.isNotEmpty()) segments.add(Segment(start, end, t))
                    }
                } else if (text.isNotBlank()) {
                    segments.add(Segment(offsetMs, offsetMs, text))
                }

                onProgress(100)
                Result(text, detectedLang, segments)
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "예외: ${e.message}")
            null
        }
    }

    private fun splitWav(input: File, maxBytes: Int): List<Pair<File, Long>> {
        val out = mutableListOf<Pair<File, Long>>()
        val bytes = input.readBytes()
        if (bytes.size < 44) return emptyList()

        val data = bytes.sliceArray(44 until bytes.size)
        val headerSampleRate = readIntLE(bytes, 24)
        val headerBitsPerSample = readShortLE(bytes, 34)
        val headerChannels = readShortLE(bytes, 22)
        val bytesPerSecond = headerSampleRate * headerChannels * headerBitsPerSample / 8
        if (bytesPerSecond <= 0) return emptyList()

        val chunkDataSize = maxBytes - 44
        var offset = 0
        var chunkIdx = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkDataSize, data.size)
            val chunkData = data.sliceArray(offset until end)
            val chunkHeader = buildWavHeader(chunkData.size, headerSampleRate, headerChannels, headerBitsPerSample)

            val outFile = File(input.parentFile, "${input.nameWithoutExtension}_chunk$chunkIdx.wav")
            outFile.outputStream().use { os ->
                os.write(chunkHeader)
                os.write(chunkData)
            }
            out.add(outFile to (offset.toLong() * 1000L) / bytesPerSecond)
            offset = end
            chunkIdx++
        }
        return out
    }

    private fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalSize = 36 + dataSize
        val header = ByteArray(44)
        System.arraycopy("RIFF".toByteArray(), 0, header, 0, 4)
        writeIntLE(header, 4, totalSize)
        System.arraycopy("WAVE".toByteArray(), 0, header, 8, 4)
        System.arraycopy("fmt ".toByteArray(), 0, header, 12, 4)
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, channels)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, blockAlign)
        writeShortLE(header, 34, bitsPerSample)
        System.arraycopy("data".toByteArray(), 0, header, 36, 4)
        writeIntLE(header, 40, dataSize)
        return header
    }

    private fun writeIntLE(arr: ByteArray, offset: Int, v: Int) {
        arr[offset] = (v and 0xFF).toByte()
        arr[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        arr[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        arr[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(arr: ByteArray, offset: Int, v: Int) {
        arr[offset] = (v and 0xFF).toByte()
        arr[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun readIntLE(arr: ByteArray, offset: Int): Int =
        (arr[offset].toInt() and 0xFF) or
        ((arr[offset + 1].toInt() and 0xFF) shl 8) or
        ((arr[offset + 2].toInt() and 0xFF) shl 16) or
        ((arr[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(arr: ByteArray, offset: Int): Int =
        (arr[offset].toInt() and 0xFF) or ((arr[offset + 1].toInt() and 0xFF) shl 8)
}
