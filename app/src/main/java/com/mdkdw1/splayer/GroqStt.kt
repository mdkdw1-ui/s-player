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
        firstChunkMaxBytes: Int = 2 * 1024 * 1024,   // 첫 청크 최대 2MB (약 1분)
        chunkMaxBytes: Int = 15 * 1024 * 1024,       // 이후 청크 15MB (약 8분)
        onChunkComplete: (suspend (segments: List<Segment>, chunkIndex: Int, totalChunks: Int) -> Unit)? = null,
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

        val chunks = splitWav(wavFile, firstChunkMaxBytes, chunkMaxBytes)
        if (chunks.isEmpty()) return@withContext null

        val total = chunks.size
        LogBus.log(TAG, "   청크 ${total}개, 동시 $CONCURRENCY 처리")

        val results = arrayOfNulls<Result>(total)
        val semaphore = Semaphore(CONCURRENCY)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val startTime = System.currentTimeMillis()

        // ===== 첫 청크 즉시 처리 (재생 시작용) =====
        try {
            val (firstFile, firstOffset) = chunks[0]
            LogBus.log(TAG, "  [첫 청크] 우선 처리 시작")
            var firstResult: Result? = null
            var attempt = 0
            while (attempt < 3 && firstResult == null) {
                try {
                    firstResult = transcribeSingle(firstFile, language, firstOffset) {}
                    if (firstResult == null) {
                        attempt++
                        if (attempt < 3) delay(2000L * attempt)
                    }
                } catch (e: Exception) {
                    attempt++
                    if (attempt < 3) delay(2000L * attempt)
                }
            }
            firstFile.delete()
            results[0] = firstResult
            val done = doneCount.incrementAndGet()
            onProgress(done * 100 / total)
            LogBus.log(TAG, "  [첫 청크] 완료 (성공=${firstResult != null})")
            
            // 첫 청크 콜백 즉시
            if (firstResult != null && onChunkComplete != null) {
                try {
                    onChunkComplete(firstResult.segments, 0, total)
                } catch (e: Exception) {
                    LogBus.log(TAG, "onChunkComplete 예외: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LogBus.log(TAG, "첫 청크 예외: ${e.message}")
        }

        // ===== 나머지 청크 병렬 =====
        try {
            coroutineScope {
                // 인덱스 1부터 (0은 이미 처리)
                chunks.drop(1).forEachIndexed { idx, (chunkFile, offsetMs) ->
                    val i = idx + 1
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
                            
                            // 청크 완료 시 실시간 콜백
                            if (result != null && onChunkComplete != null) {
                                try {
                                    onChunkComplete(result.segments, i, total)
                                } catch (e: Exception) {
                                    LogBus.log(TAG, "onChunkComplete 예외: ${e.message}")
                                }
                            }
                            
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
                .addFormDataPart("timestamp_granularities[]", "word")   // ★ word-level 추가
                .addFormDataPart("temperature", "0")
                .addFormDataPart("prompt", "")

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

                // ===== 환각 필터 + Word-based 재구성 =====
                val segments = mutableListOf<Segment>()

                // 1) 원본 세그먼트에서 환각 필터
                val filteredSegments = mutableListOf<Pair<Double, Double>>()  // (start, end) 신뢰 구간
                val segsArr = json.optJSONArray("segments")
                if (segsArr != null) {
                    for (i in 0 until segsArr.length()) {
                        val s = segsArr.getJSONObject(i)
                        val noSpeech = s.optDouble("no_speech_prob", 0.0)
                        val avgLogprob = s.optDouble("avg_logprob", 0.0)
                        val compression = s.optDouble("compression_ratio", 0.0)
                        val start = s.optDouble("start", 0.0)
                        val end = s.optDouble("end", 0.0)
                        val segText = s.optString("text", "").trim()

                        // 환각 조건
                        val isHallucination =
                            noSpeech > 0.6 ||                          // 무음 확률 높음
                            avgLogprob < -1.5 ||                       // 신뢰도 낮음
                            compression > 2.4 ||                       // 반복 텍스트
                            (end - start) < 0.3 ||                     // 0.3초 미만
                            segText.length < 2 ||                      // 1글자 이하
                            segText.matches(Regex("^(ん|음|♪|\\.+|。+|、+)+$"))  // 노이즈만

                        if (!isHallucination) {
                            filteredSegments.add(start to end)
                        } else {
                            LogBus.log(TAG, "  [필터] 환각 제거: '$segText' (noSpeech=$noSpeech, logprob=$avgLogprob)")
                        }
                    }
                }

                // 2) Word-level 데이터로 세그먼트 재구성 (더 정확)
                val wordsArr = json.optJSONArray("words")
                if (wordsArr != null && wordsArr.length() > 0) {
                    data class W(val start: Double, val end: Double, val text: String)
                    val words = mutableListOf<W>()
                    for (i in 0 until wordsArr.length()) {
                        val w = wordsArr.getJSONObject(i)
                        val ws = w.optDouble("start", 0.0)
                        val we = w.optDouble("end", 0.0)
                        val wt = w.optString("word", "")
                        if (wt.isNotBlank() && we > ws) {
                            words.add(W(ws, we, wt))
                        }
                    }

                    // 무음 간격 기준으로 그룹핑 (0.4초 이상 갭이면 새 자막)
                    val GAP_THRESHOLD = 0.4
                    val MAX_SEGMENT_DURATION = 6.0   // 최대 6초

                    var curStart = words[0].start
                    var curEnd = words[0].end
                    val curText = StringBuilder(words[0].text)

                    for (i in 1 until words.size) {
                        val w = words[i]
                        val gap = w.start - curEnd
                        val segDuration = curEnd - curStart

                        if (gap > GAP_THRESHOLD || segDuration > MAX_SEGMENT_DURATION) {
                            // 새 세그먼트
                            val startMs = (curStart * 1000).toLong() + offsetMs
                            val endMs = (curEnd * 1000).toLong() + offsetMs
                            val txt = curText.toString().trim()
                            if (txt.isNotEmpty()) {
                                segments.add(Segment(startMs, endMs, txt))
                            }
                            curStart = w.start
                            curEnd = w.end
                            curText.clear()
                            curText.append(w.text)
                        } else {
                            curEnd = w.end
                            curText.append(w.text)
                        }
                    }
                    // 마지막 세그먼트
                    val startMs = (curStart * 1000).toLong() + offsetMs
                    val endMs = (curEnd * 1000).toLong() + offsetMs
                    val txt = curText.toString().trim()
                    if (txt.isNotEmpty()) {
                        segments.add(Segment(startMs, endMs, txt))
                    }

                    LogBus.log(TAG, "  Word-based 재구성: ${words.size}단어 → ${segments.size}세그먼트")
                } else if (filteredSegments.isNotEmpty()) {
                    // Word 데이터 없으면 세그먼트 기반 (필터만 적용)
                    val segsArr2 = json.optJSONArray("segments")
                    if (segsArr2 != null) {
                        for (i in 0 until segsArr2.length()) {
                            val s = segsArr2.getJSONObject(i)
                            val start = s.optDouble("start", 0.0)
                            val end = s.optDouble("end", 0.0)
                            val t = s.optString("text", "").trim()
                            if (t.isNotEmpty() && filteredSegments.any { it.first == start }) {
                                segments.add(Segment(
                                    (start * 1000).toLong() + offsetMs,
                                    (end * 1000).toLong() + offsetMs,
                                    t
                                ))
                            }
                        }
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

    private fun splitWav(
        input: File,
        firstMaxBytes: Int,
        restMaxBytes: Int
    ): List<Pair<File, Long>> {
        val out = mutableListOf<Pair<File, Long>>()
        val bytes = input.readBytes()
        if (bytes.size < 44) return emptyList()

        val data = bytes.sliceArray(44 until bytes.size)
        val headerSampleRate = readIntLE(bytes, 24)
        val headerBitsPerSample = readShortLE(bytes, 34)
        val headerChannels = readShortLE(bytes, 22)
        val bytesPerSecond = headerSampleRate * headerChannels * headerBitsPerSample / 8
        if (bytesPerSecond <= 0) return emptyList()

        var offset = 0
        var chunkIdx = 0
        while (offset < data.size) {
            val maxBytes = if (chunkIdx == 0) firstMaxBytes else restMaxBytes
            val chunkDataSize = maxBytes - 44
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
        LogBus.log(TAG, "청크 분할: 총 ${out.size}개 (첫 번째 ${firstMaxBytes/1024}KB, 이후 ${restMaxBytes/1024}KB)")
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
