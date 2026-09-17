package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    // Groq 파일 크기 제한 25MB. 안전하게 20MB 로 청크.
    // 16kHz 16bit mono = 32KB/s → 20MB = 약 10분
    private const val MAX_CHUNK_BYTES = 20 * 1024 * 1024

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    data class Result(
        val text: String,
        val language: String?,
        val segments: List<Segment>
    )

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

        if (wavFile.length() <= MAX_CHUNK_BYTES) {
            // 단일 파일
            return@withContext transcribeSingle(wavFile, language, 0L, onProgress)
        }

        // 청크 분할
        LogBus.log(TAG, "큰 파일 (${wavFile.length() / 1024 / 1024}MB) → 청크 분할")

        val chunks = splitWav(wavFile, MAX_CHUNK_BYTES)
        LogBus.log(TAG, "청크 ${chunks.size}개")

        val allSegments = mutableListOf<Segment>()
        var detectedLang: String? = null
        val fullText = StringBuilder()

        chunks.forEachIndexed { idx, (chunkFile, offsetMs) ->
            onProgress((idx * 100) / chunks.size)
            LogBus.log(TAG, "청크 ${idx + 1}/${chunks.size} (offset=${offsetMs}ms)")

            val result = transcribeSingle(chunkFile, language, offsetMs) { p ->
                val base = (idx * 100) / chunks.size
                val span = 100 / chunks.size
                onProgress(base + (p * span / 100))
            }

            chunkFile.delete()  // 임시 파일 정리

            if (result != null) {
                allSegments.addAll(result.segments)
                if (detectedLang == null) detectedLang = result.language
                fullText.append(result.text).append(" ")
            } else {
                LogBus.log(TAG, "청크 ${idx + 1} 실패")
            }
        }

        onProgress(100)
        Result(
            text = fullText.toString().trim(),
            language = detectedLang,
            segments = allSegments
        )
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
            LogBus.log(TAG, "업로드: ${wavFile.length() / 1024}KB")

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("temperature", "0")

            if (language != "auto") {
                bodyBuilder.addFormDataPart("language", language)
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            onProgress(15)

            client.newCall(request).execute().use { resp ->
                onProgress(80)
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "HTTP ${resp.code}: ${bodyStr.take(200)}")
                    return@withContext null
                }

                val json = JSONObject(bodyStr)
                val text = json.optString("text", "")
                val detectedLang: String? = if (json.has("language") && !json.isNull("language")) {
                    json.getString("language")
                } else null

                val segments = mutableListOf<Segment>()
                val segsArr = json.optJSONArray("segments")
                if (segsArr != null) {
                    for (i in 0 until segsArr.length()) {
                        val s = segsArr.getJSONObject(i)
                        val start = (s.optDouble("start", 0.0) * 1000).toLong() + offsetMs
                        val end = (s.optDouble("end", 0.0) * 1000).toLong() + offsetMs
                        val t = s.optString("text", "").trim()
                        if (t.isNotEmpty()) {
                            segments.add(Segment(start, end, t))
                        }
                    }
                } else if (text.isNotBlank()) {
                    segments.add(Segment(offsetMs, offsetMs, text))
                }

                onProgress(100)
                LogBus.log(TAG, "완료: ${segments.size} 세그먼트, 언어=$detectedLang")
                Result(text = text, language = detectedLang, segments = segments)
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "예외: ${e.message}")
            Log.e(TAG, "Groq 오류", e)
            null
        }
    }

    /**
     * WAV 파일을 최대 크기로 분할. 각 청크는 독립적인 WAV 헤더를 가짐.
     * @return (chunkFile, offsetMs) 리스트
     */
    private fun splitWav(input: File, maxBytes: Int): List<Pair<File, Long>> {
        val out = mutableListOf<Pair<File, Long>>()
        val bytes = input.readBytes()

        // WAV 헤더 = 44 바이트
        if (bytes.size < 44) return emptyList()
        val header = bytes.sliceArray(0 until 44)
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

            // 새 WAV 헤더 생성
            val chunkHeader = buildWavHeader(
                dataSize = chunkData.size,
                sampleRate = headerSampleRate,
                channels = headerChannels,
                bitsPerSample = headerBitsPerSample
            )

            val outFile = File(input.parentFile, "${input.nameWithoutExtension}_chunk$chunkIdx.wav")
            outFile.outputStream().use { os ->
                os.write(chunkHeader)
                os.write(chunkData)
            }

            val offsetMs = (offset.toLong() * 1000L) / bytesPerSecond
            out.add(outFile to offsetMs)

            offset = end
            chunkIdx++
        }

        return out
    }

    private fun buildWavHeader(
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
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

    private fun readIntLE(arr: ByteArray, offset: Int): Int {
        return (arr[offset].toInt() and 0xFF) or
               ((arr[offset + 1].toInt() and 0xFF) shl 8) or
               ((arr[offset + 2].toInt() and 0xFF) shl 16) or
               ((arr[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(arr: ByteArray, offset: Int): Int {
        return (arr[offset].toInt() and 0xFF) or
               ((arr[offset + 1].toInt() and 0xFF) shl 8)
    }
}
