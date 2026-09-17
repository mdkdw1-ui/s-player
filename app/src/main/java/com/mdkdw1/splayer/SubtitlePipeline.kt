package com.mdkdw1.splayer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mdkdw1.splayer.audio.AudioDecoder
import com.mdkdw1.splayer.audio.AudioPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SubtitlePipeline {

    private const val TAG = "SubtitlePipeline"

    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val original: String,
        val translated: String
    )

    data class Progress(
        val stage: String,
        val percent: Int,
        val message: String = ""
    )

    // ================== 로컬 파일 ==================
    suspend fun run(
        context: Context,
        sourceUri: Uri,
        model: WhisperModel,
        sourceLang: String = "auto",
        targetLang: String = "ko",
        onProgress: (Progress) -> Unit,
        onSegment: (Segment) -> Unit,
        onLanguageDetected: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        LogBus.log(TAG, "=== 로컬 START (model=${model.id}, sourceLang=$sourceLang)")

        onProgress(Progress("copy", 0, "파일 복사 중..."))
        val inputFile = copyToCache(context, sourceUri)
        if (inputFile == null) {
            onProgress(Progress("error", 0, "파일 복사 실패"))
            return@withContext null
        }
        onProgress(Progress("copy", 100, "복사 완료"))

        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()
        val ok = try { AudioDecoder.decodeToWav(inputFile, wavFile) }
        catch (e: Exception) { LogBus.log(TAG, "디코딩 예외: ${e.message}"); false }
        if (!ok || !wavFile.exists()) {
            onProgress(Progress("error", 0, "디코딩 실패"))
            return@withContext null
        }
        onProgress(Progress("decode", 100, "디코딩 완료"))

        runSttAndTranslate(context, wavFile, model, sourceLang, targetLang, onProgress, onSegment, null, onLanguageDetected)
    }

    // ================== URL ==================
    suspend fun runFromUrl(
        context: Context,
        url: String,
        model: WhisperModel,
        sourceLang: String = "auto",
        targetLang: String = "ko",
        onProgress: (Progress) -> Unit,
        onSegment: (Segment) -> Unit,
        onStreamInfo: (StreamResult) -> Unit = {},
        onLanguageDetected: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        LogBus.log(TAG, "=== URL START (model=${model.id}, sourceLang=$sourceLang): $url")

        // 캐시 확인
        if (SubtitleCache.exists(context, url, targetLang)) {
            LogBus.log(TAG, "캐시 히트!")
            onProgress(Progress("cache", 100, "캐시된 자막 로드"))
            val srt = SubtitleCache.read(context, url, targetLang)
            if (srt != null) {
                SubtitleCache.parseSrt(srt).forEach { onSegment(it) }
                return@withContext SubtitleCache.srtFile(context, url, targetLang)
            }
        }

        // 스트림 추출
        onProgress(Progress("extract", 0, "영상 정보 추출 중..."))
        val info = StreamExtractor.extract(url).getOrNull()
        if (info == null) {
            onProgress(Progress("error", 0, "스트림 추출 실패"))
            return@withContext null
        }
        onStreamInfo(info)
        onProgress(Progress("extract", 100, "${info.title} (${info.durationSec}s)"))

        val audioUrl = info.audioUrl ?: info.videoUrl
        if (audioUrl == null) {
            onProgress(Progress("error", 0, "오디오 스트림 없음"))
            return@withContext null
        }
        val ext = guessExt(info.audioMimeType ?: info.videoMimeType)

        // 다운로드
        onProgress(Progress("download", 0, "다운로드 시작..."))
        val audioFile = StreamDownloader.download(context, audioUrl, "url_input", ext) { p ->
            onProgress(Progress("download", (p * 100).toInt(), "다운로드 ${(p*100).toInt()}%"))
        }
        if (audioFile == null) {
            onProgress(Progress("error", 0, "다운로드 실패"))
            return@withContext null
        }
        onProgress(Progress("download", 100, "다운로드 완료"))

        // 디코딩
        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()
        val decodeOk = try { AudioDecoder.decodeToWav(audioFile, wavFile) }
        catch (e: Exception) { LogBus.log(TAG, "디코딩 예외: ${e.message}"); false }
        if (!decodeOk || !wavFile.exists()) {
            onProgress(Progress("error", 0, "디코딩 실패"))
            return@withContext null
        }
        onProgress(Progress("decode", 100, "디코딩 완료"))

        runSttAndTranslate(context, wavFile, model, sourceLang, targetLang, onProgress, onSegment, url, onLanguageDetected)
    }

    // ================== STT + 번역 공통 ==================
    private suspend fun runSttAndTranslate(
        context: Context,
        wavFile: File,
        model: WhisperModel,
        sourceLang: String,
        targetLang: String,
        onProgress: (Progress) -> Unit,
        onSegment: (Segment) -> Unit,
        cacheSourceKey: String?,
        onLanguageDetected: (String) -> Unit
    ): File? {

        val rawSegments: List<Pair<Long, Long>> = emptyList()  // (start, end) placeholder
        val rawTexts: MutableList<Triple<Long, Long, String>> = mutableListOf()
        var detectedLang: String? = null

        if (model.isCloud) {
            // ===== Groq =====
            onProgress(Progress("stt", 0, "Groq 업로드 중... (${model.displayName})"))

            val groqResult = GroqStt.transcribe(wavFile, sourceLang) { p ->
                onProgress(Progress("stt", p, "Groq 처리 중 $p%"))
            }

            if (groqResult == null) {
                onProgress(Progress("error", 0, "Groq 실패"))
                return null
            }

            detectedLang = groqResult.language
            detectedLang?.let { onLanguageDetected(it) }

            groqResult.segments.forEach { s ->
                rawTexts.add(Triple(s.startMs, s.endMs, s.text))
            }
            LogBus.log(TAG, "Groq 세그먼트: ${rawTexts.size}개, 언어=${detectedLang}")

        } else {
            // ===== 로컬 Whisper =====
            if (!WhisperModelDownloader.isInstalled(context, model)) {
                onProgress(Progress("error", 0, "모델 미설치"))
                return null
            }
            val cachedModel = WhisperModelStorage.copyToCache(context, model)
            if (cachedModel == null || !cachedModel.exists()) {
                onProgress(Progress("error", 0, "모델 캐시 복사 실패"))
                return null
            }
            val modelPath = cachedModel.absolutePath
            LogBus.log(TAG, "로컬 모델: $modelPath")

            onProgress(Progress("stt", 0, "음성 인식 중..."))
            val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
            val queue = java.util.concurrent.LinkedBlockingQueue<Triple<Long, Long, String>>()

            val sttOk = try {
                WhisperBridge.transcribe(
                    modelPath = modelPath,
                    wavPath = wavFile.absolutePath,
                    language = sourceLang,
                    threads = threads,
                    callback = object : WhisperBridge.SegmentCallback {
                        override fun onSegment(startMs: Long, endMs: Long, text: String) {
                            val t = text.trim()
                            if (t.isEmpty()) return
                            queue.put(Triple(startMs, endMs, t))
                        }
                        override fun onProgress(percent: Int) {
                            if (percent % 5 == 0 || percent >= 99) {
                                onProgress(Progress("stt", percent, "인식 중 $percent%"))
                            }
                        }
                        override fun onComplete() {}
                        override fun onLog(msg: String) { LogBus.log("JNI", msg) }
                        override fun onLanguage(langCode: String) {
                            detectedLang = langCode
                            LogBus.log(TAG, "감지 언어: $langCode")
                            onLanguageDetected(langCode)
                        }
                    }
                )
            } catch (e: Exception) { LogBus.log(TAG, "STT 예외: ${e.message}"); false }

            if (!sttOk) {
                onProgress(Progress("error", 0, "STT 실패"))
                return null
            }

            // 큐에서 세그먼트 꺼내기
            while (true) {
                val s = try { queue.poll() } catch (e: Exception) { null } ?: break
                rawTexts.add(s)
            }
        }

        // ===== 번역 =====
        val actualSource = when {
            sourceLang != "auto" -> sourceLang
            detectedLang != null -> detectedLang!!
            else -> "en"
        }
        LogBus.log(TAG, "번역 시작 (source=$actualSource, target=$targetLang, detected=$detectedLang, manual=$sourceLang)")
        onProgress(Progress("translate", 0, "번역 중 ($actualSource → $targetLang)"))

        val translator = GoogleTranslator()
        val collected = mutableListOf<Segment>()
        val lock = Object()

        val total = rawTexts.size
        rawTexts.forEachIndexed { i, (start, end, text) ->
            val translated = try {
                if (targetLang == actualSource) text
                else {
                    val t = kotlinx.coroutines.runBlocking {
                        translator.translate(text, targetLang, actualSource)
                    }
                    if (t == text) "" else t
                }
            } catch (e: Exception) { "" }

            val seg = Segment(start, end, text, translated)
            synchronized(lock) { collected.add(seg) }
            onSegment(seg)
            onProgress(Progress("translate", (i+1) * 100 / total.coerceAtLeast(1), "번역 ${i+1}/$total"))
        }

        val rawSegments = synchronized(lock) { collected.toList() }
        LogBus.log(TAG, "원본: ${rawSegments.size} 세그먼트")

        // 세그먼트 병합 (가독성)
        val finalSegments = mergeSegments(rawSegments)
        LogBus.log(TAG, "병합 후: ${finalSegments.size} 세그먼트")

        val srtFile = File(AudioPaths.subtitleDir(context), "test_${targetLang}.srt")
        writeSrt(srtFile, finalSegments)
        LogBus.log(TAG, "SRT: ${srtFile.absolutePath}")

        if (cacheSourceKey != null) {
            SubtitleCache.write(context, cacheSourceKey, targetLang, srtFile.readText())
        }

        onProgress(Progress("done", 100, "완료: ${finalSegments.size} 세그먼트"))
        return srtFile
    }

    // ================== 세그먼트 병합 ==================
    /**
     * 자막 가독성을 위해 세그먼트 병합.
     * - 최소 표시 시간 1.5초
     * - 최대 표시 시간 6초
     * - 최대 글자 수 80자 (한국어 기준)
     */
    private fun mergeSegments(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments

        val out = mutableListOf<Segment>()
        var cur = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            val durationMs = cur.endMs - cur.startMs
            val gap = next.startMs - cur.endMs

            val combinedText = (cur.original + " " + next.original).trim()
            val combinedTrans = (cur.translated + " " + next.translated).trim()

            val shouldMerge = durationMs < 1500 &&
                gap < 500 &&
                combinedText.length < 120 &&
                combinedTrans.length < 80

            if (shouldMerge) {
                cur = cur.copy(
                    endMs = next.endMs,
                    original = combinedText,
                    translated = combinedTrans
                )
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    // ================== 유틸 ==================
    private fun guessExt(mime: String?): String = when {
        mime == null -> "m4a"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.contains("webm") -> "webm"
        mime.contains("ogg") -> "ogg"
        mime.contains("mpeg") -> "mp3"
        else -> "m4a"
    }

    private fun copyToCache(context: Context, uri: Uri): File? = try {
        val name = queryFileName(context, uri) ?: "input_${System.currentTimeMillis()}"
        val ext = name.substringAfterLast('.', "bin")
        val dest = AudioPaths.tempAudioInput(context, "input", ext)
        if (dest.exists()) dest.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { it.write(input.readBytes()) }
        }
        dest
    } catch (e: Exception) { null }

    private fun queryFileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    private fun writeSrt(file: File, segments: List<Segment>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            sb.append(i + 1).append('\n')
            sb.append(formatTime(seg.startMs)).append(" --> ").append(formatTime(seg.endMs)).append('\n')
            sb.append(if (seg.translated.isNotBlank()) seg.translated else seg.original).append('\n').append('\n')
        }
        file.writeText(sb.toString())
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000; val msec = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, msec)
    }
}
