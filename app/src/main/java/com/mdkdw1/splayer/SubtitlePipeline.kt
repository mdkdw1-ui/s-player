package com.mdkdw1.splayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mdkdw1.splayer.audio.AudioDecoder
import com.mdkdw1.splayer.audio.AudioPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object SubtitlePipeline {

    private const val TAG = "SubtitlePipeline"
    private const val SEP = " ||| "

    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val original: String,
        val translated: String
    )

    data class Progress(
        val stage: String,
        val percent: Int,
        val message: String = "",
        val engine: String? = null
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
        LogBus.log(TAG, "=== 로컬 START (model=${model.id})")
        onProgress(Progress("copy", 0, "파일 복사 중..."))
        val inputFile = copyToCache(context, sourceUri)
        if (inputFile == null) { onProgress(Progress("error", 0, "복사 실패")); return@withContext null }
        onProgress(Progress("copy", 100, "복사 완료"))

        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()
        val ok = try { AudioDecoder.decodeToWav(inputFile, wavFile) }
        catch (e: Exception) { LogBus.log(TAG, "디코딩 예외: ${e.message}"); false }
        if (!ok || !wavFile.exists()) { onProgress(Progress("error", 0, "디코딩 실패")); return@withContext null }
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
        LogBus.log(TAG, "=== URL START (model=${model.id})")

        if (SubtitleCache.exists(context, url, targetLang)) {
            LogBus.log(TAG, "캐시 히트!")
            onProgress(Progress("cache", 100, "캐시된 자막 로드", "cache"))
            val srt = SubtitleCache.read(context, url, targetLang)
            if (srt != null) {
                SubtitleCache.parseSrt(srt).forEach { onSegment(it) }
                return@withContext SubtitleCache.srtFile(context, url, targetLang)
            }
        }

        onProgress(Progress("extract", 0, "영상 정보 추출 중..."))
        val info = StreamExtractor.extract(url).getOrNull()
        if (info == null) { onProgress(Progress("error", 0, "스트림 추출 실패")); return@withContext null }
        onStreamInfo(info)
        onProgress(Progress("extract", 100, "${info.title} (${info.durationSec}s)"))

        val audioUrl = info.audioUrl ?: info.videoUrl
        if (audioUrl == null) { onProgress(Progress("error", 0, "오디오 스트림 없음")); return@withContext null }
        val ext = guessExt(info.audioMimeType ?: info.videoMimeType)

        onProgress(Progress("download", 0, "다운로드 시작..."))
        val audioFile = StreamDownloader.download(context, audioUrl, "url_input", ext) { p ->
            onProgress(Progress("download", (p * 100).toInt(), "다운로드 ${(p*100).toInt()}%"))
        }
        if (audioFile == null) { onProgress(Progress("error", 0, "다운로드 실패")); return@withContext null }
        onProgress(Progress("download", 100, "다운로드 완료"))

        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()
        val decodeOk = try { AudioDecoder.decodeToWav(audioFile, wavFile) }
        catch (e: Exception) { LogBus.log(TAG, "디코딩 예외: ${e.message}"); false }
        if (!decodeOk || !wavFile.exists()) { onProgress(Progress("error", 0, "디코딩 실패")); return@withContext null }
        onProgress(Progress("decode", 100, "디코딩 완료"))

        runSttAndTranslate(context, wavFile, model, sourceLang, targetLang, onProgress, onSegment, url, onLanguageDetected)
    }

    // ================== STT + 번역 ==================
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

        val rawTexts = mutableListOf<Triple<Long, Long, String>>()
        var detectedLang: String? = null

        // ---------- STT ----------
        if (model.isCloud) {
            onProgress(Progress("stt", 0, "Groq 처리 중...", "groq"))
            val r = GroqStt.transcribe(wavFile, sourceLang) { p ->
                onProgress(Progress("stt", p, "Groq $p%", "groq"))
            }
            if (r == null) { onProgress(Progress("error", 0, "Groq 실패")); return null }
            detectedLang = r.language
            detectedLang?.let { onLanguageDetected(it) }
            r.segments.forEach { s -> rawTexts.add(Triple(s.startMs, s.endMs, s.text)) }
        } else {
            if (!WhisperModelDownloader.isInstalled(context, model)) {
                onProgress(Progress("error", 0, "모델 미설치")); return null
            }
            val cachedModel = WhisperModelStorage.copyToCache(context, model) ?: return null
            onProgress(Progress("stt", 0, "음성 인식 중...", "local"))
            val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
            val queue = java.util.concurrent.LinkedBlockingQueue<Triple<Long, Long, String>>()

            val sttOk = try {
                WhisperBridge.transcribe(
                    modelPath = cachedModel.absolutePath,
                    wavPath = wavFile.absolutePath,
                    language = sourceLang,
                    threads = threads,
                    callback = object : WhisperBridge.SegmentCallback {
                        override fun onSegment(startMs: Long, endMs: Long, text: String) {
                            val t = text.trim()
                            if (t.isNotEmpty()) queue.put(Triple(startMs, endMs, t))
                        }
                        override fun onProgress(percent: Int) {
                            if (percent % 5 == 0 || percent >= 99) onProgress(Progress("stt", percent, "인식 $percent%", "local"))
                        }
                        override fun onComplete() {}
                        override fun onLog(msg: String) { LogBus.log("JNI", msg) }
                        override fun onLanguage(langCode: String) {
                            detectedLang = langCode
                            onLanguageDetected(langCode)
                        }
                    }
                )
            } catch (e: Exception) { LogBus.log(TAG, "STT 예외: ${e.message}"); false }

            if (!sttOk) { onProgress(Progress("error", 0, "STT 실패")); return null }
            while (true) {
                val s = queue.poll() ?: break
                rawTexts.add(s)
            }
        }

        LogBus.log(TAG, "STT 완료: ${rawTexts.size} 세그먼트")

        // ---------- 언어 정규화 ----------
        fun normalizeLang(lang: String): String = when (lang.lowercase()) {
            "japanese", "ja", "jp" -> "ja"
            "korean", "ko", "kr" -> "ko"
            "english", "en" -> "en"
            "chinese", "zh", "cn" -> "zh"
            "spanish", "es" -> "es"
            "french", "fr" -> "fr"
            "german", "de" -> "de"
            else -> lang.lowercase().take(2)
        }

        val actualSource = when {
            sourceLang != "auto" -> normalizeLang(sourceLang)
            detectedLang != null -> normalizeLang(detectedLang!!)
            else -> "ja"
        }

        val collected = mutableListOf<Segment>()

        // ---------- 번역 ----------
        if (targetLang == actualSource) {
            rawTexts.forEach { (s, e, t) -> collected.add(Segment(s, e, t, t)) }
        } else if (TexTraTranslator.isConfigured()) {
            // ===== TexTra: 병렬 번역 =====
            LogBus.log(TAG, "★★★ TexTra 병렬 번역 모드 (동시 3개) ★★★")
            LogBus.log(TAG, "   총 ${rawTexts.size}개, src=$actualSource → tgt=$targetLang")
            onProgress(Progress("translate", 0, "TexTra 병렬 번역 시작...", "textra"))

            val total = rawTexts.size
            val results = arrayOfNulls<String>(total)
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)  // 동시 3개
            var doneCount = java.util.concurrent.atomic.AtomicInteger(0)
            var successCount = java.util.concurrent.atomic.AtomicInteger(0)

            val startTime = System.currentTimeMillis()

            kotlinx.coroutines.coroutineScope {
                rawTexts.forEachIndexed { i, (_, _, t) ->
                    kotlinx.coroutines.launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val tr = try {
                                TexTraTranslator.translate(t, targetLang, actualSource) ?: ""
                            } catch (ex: Exception) {
                                LogBus.log(TAG, "TexTra [$i] 예외: ${ex.message}")
                                ""
                            }
                            results[i] = tr
                            if (tr.isNotBlank()) successCount.incrementAndGet()

                            val done = doneCount.incrementAndGet()
                            val elapsed = System.currentTimeMillis() - startTime
                            val eta = if (done > 0) (elapsed * (total - done) / done) / 1000 else 0
                            onProgress(
                                Progress(
                                    "translate",
                                    done * 100 / total,
                                    "TexTra $done/$total (성공 ${successCount.get()}, 남은 ${eta}초)",
                                    "textra"
                                )
                            )
                            LogBus.log(TAG, "  [$done/$total] ${t.take(20)} → ${tr.take(20)}")
                        }
                    }
                }
            }

            // 결과 순서대로 수집
            rawTexts.forEachIndexed { i, (s, e, t) ->
                collected.add(Segment(s, e, t, results[i] ?: ""))
            }
            val elapsedTotal = (System.currentTimeMillis() - startTime) / 1000.0
            LogBus.log(TAG, "★★★ TexTra 완료: ${successCount.get()}/${total} 성공 (%.1f초) ★★★".format(elapsedTotal))
        } else {
            // ===== Google: 통번역 =====
            val combinedText = rawTexts.joinToString(SEP) { it.third }
            LogBus.log(TAG, "★★★ Google 통번역 모드 ★★★")
            LogBus.log(TAG, "   ${rawTexts.size}개, ${combinedText.length}자, src=$actualSource")
            onProgress(Progress("translate", 0, "Google 통번역 ($actualSource → $targetLang)", "google"))

            val combinedTrans: String = try {
                GoogleTranslator().translate(combinedText, targetLang, actualSource)
            } catch (e: Exception) {
                LogBus.log(TAG, "통번역 예외: ${e.message}")
                ""
            }

            if (combinedTrans.isBlank()) {
                LogBus.log(TAG, "통번역 실패 → 개별 번역 폴백")
                val g = GoogleTranslator()
                rawTexts.forEachIndexed { i, (s, e, t) ->
                    val tr = try { g.translate(t, targetLang, actualSource) } catch (ex: Exception) { "" }
                    collected.add(Segment(s, e, t, tr))
                    onProgress(Progress("translate", (i + 1) * 100 / rawTexts.size, "번역 ${i + 1}/${rawTexts.size}", "google"))
                }
            } else {
                val parts = combinedTrans.split(SEP).map { it.trim() }
                if (parts.size == rawTexts.size) {
                    rawTexts.forEachIndexed { i, (s, e, t) ->
                        collected.add(Segment(s, e, t, parts[i]))
                    }
                    LogBus.log(TAG, "통번역 성공: ${parts.size}개 매칭")
                } else {
                    LogBus.log(TAG, "세그먼트 수 불일치: 원본 ${rawTexts.size}, 번역 ${parts.size}")
                    var pi = 0
                    rawTexts.forEachIndexed { i, (s, e, t) ->
                        val remainSegs = rawTexts.size - i
                        val remainParts = parts.size - pi
                        if (remainSegs <= 0 || pi >= parts.size) {
                            collected.add(Segment(s, e, t, ""))
                        } else {
                            val take = if (remainSegs == 1) remainParts else maxOf(1, remainParts / remainSegs)
                            val chunk = parts.subList(pi, minOf(pi + take, parts.size)).joinToString(" ")
                            pi += take
                            collected.add(Segment(s, e, t, chunk))
                        }
                    }
                }
            }
        }

        // ---------- 병합 ----------
        val merged = mergeSegments(collected)
        LogBus.log(TAG, "병합 후: ${merged.size} 세그먼트")

        merged.forEach { onSegment(it) }

        val srtFile = File(AudioPaths.subtitleDir(context), "test_${targetLang}.srt")
        writeSrt(srtFile, merged)
        LogBus.log(TAG, "SRT: ${srtFile.absolutePath}")

        if (cacheSourceKey != null) {
            SubtitleCache.write(context, cacheSourceKey, targetLang, srtFile.readText())
        }

        val finalEngine = if (TexTraTranslator.isConfigured()) "textra" else "google"
        onProgress(Progress("done", 100, "완료: ${merged.size} 세그먼트 ($finalEngine)", finalEngine))
        return srtFile
    }

    // ================== 병합 ==================
    private fun mergeSegments(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments
        val out = mutableListOf<Segment>()
        var cur = segments[0]
        for (i in 1 until segments.size) {
            val next = segments[i]
            val duration = cur.endMs - cur.startMs
            val gap = next.startMs - cur.endMs
            val combinedText = (cur.original + " " + next.original).trim()
            val combinedTrans = (cur.translated + " " + next.translated).trim()
            val shouldMerge = duration < 1500 && gap < 500 &&
                combinedText.length < 120 && combinedTrans.length < 80
            if (shouldMerge) {
                cur = cur.copy(endMs = next.endMs, original = combinedText, translated = combinedTrans)
            } else {
                out.add(cur); cur = next
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
