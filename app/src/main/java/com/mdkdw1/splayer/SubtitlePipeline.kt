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
        LogBus.log(TAG, "=== 로컬 START")
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

        runWhisperAndSrt(context, wavFile, model, sourceLang, targetLang, onProgress, onSegment, null, onLanguageDetected)
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
        LogBus.log(TAG, "=== URL START: $url")

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

        // 1. 스트림 추출
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

        // 2. 순차 다운로드
        onProgress(Progress("download", 0, "다운로드 시작..."))
        val audioFile = StreamDownloader.download(context, audioUrl, "url_input", ext) { p ->
            onProgress(Progress("download", (p * 100).toInt(), "다운로드 ${(p*100).toInt()}%"))
        }
        if (audioFile == null) {
            onProgress(Progress("error", 0, "다운로드 실패"))
            return@withContext null
        }
        onProgress(Progress("download", 100, "다운로드 완료 (${audioFile.length() / 1024}KB)"))

        // 3. 디코딩
        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()
        val decodeOk = try { AudioDecoder.decodeToWav(audioFile, wavFile) }
        catch (e: Exception) {
            LogBus.log(TAG, "디코딩 예외: ${e.message}"); false
        }
        if (!decodeOk || !wavFile.exists()) {
            onProgress(Progress("error", 0, "디코딩 실패"))
            return@withContext null
        }
        LogBus.log(TAG, "WAV: ${wavFile.length()} bytes")
        onProgress(Progress("decode", 100, "디코딩 완료"))

        runWhisperAndSrt(context, wavFile, model, sourceLang, targetLang, onProgress, onSegment, url, onLanguageDetected)
    }

    // ================== 공통: Whisper + 번역 + SRT ==================
    private suspend fun runWhisperAndSrt(
        context: Context,
        wavFile: File,
        model: WhisperModel,
        sourceLang: String,
        targetLang: String,
        onProgress: (Progress) -> Unit,
        onSegment: (Segment) -> Unit,
        cacheSourceKey: String?,
        onLanguageDetected: (String) -> Unit = {}
    ): File? {
        if (!WhisperModelDownloader.isInstalled(context, model)) {
            onProgress(Progress("error", 0, "모델 미설치"))
            return null
        }
        val modelPath = WhisperModelDownloader.modelFile(context, model).absolutePath

        val collected = mutableListOf<Segment>()
        val translator = GoogleTranslator()
        val queue = java.util.concurrent.LinkedBlockingQueue<Segment>()
        val lock = Object()

        // Whisper 가 감지한 원본 언어 (콜백으로 채워짐)
        var detectedLang: String? = null

        // 번역 스레드는 Whisper 완료 후 시작 (감지 언어 확정 후)
        // 지금은 세그먼트만 모아둠
        var translatorThread: Thread? = null

        onProgress(Progress("stt", 0, "음성 인식 중..."))
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)

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
                        queue.put(Segment(startMs, endMs, t, ""))
                    }
                    override fun onProgress(percent: Int) {
                        onProgress(Progress("stt", percent, "인식 중 $percent%"))
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

        // Whisper 완료 후 번역 시작
        // 수동 선택이 있으면 그걸 최우선, 없으면 감지 언어, 마지막으로 sourceLang
        val actualSource = if (sourceLang != "auto") sourceLang
                          else detectedLang ?: "en"
        LogBus.log(TAG, "번역 시작 (source=$actualSource, target=$targetLang, detected=$detectedLang)")
        translatorThread = Thread {
            while (true) {
                val seg = try { queue.take() } catch (e: Exception) { break }
                if (seg.original == "__DONE__") break
                val translated = try {
                    if (targetLang == actualSource) seg.original
                    else {
                        val t = kotlinx.coroutines.runBlocking { translator.translate(seg.original, targetLang, actualSource) }
                        // 번역 결과가 원문과 같으면 빈 문자열 (원문 표시만)
                        if (t == seg.original) "" else t
                    }
                } catch (e: Exception) { "" }
                val finalSeg = seg.copy(translated = translated)
                synchronized(lock) { collected.add(finalSeg) }
                onSegment(finalSeg)
                LogBus.log(TAG, "SEG [${finalSeg.startMs}ms] ${finalSeg.original.take(40)}")
            }
        }
        translatorThread.start()
        queue.put(Segment(0, 0, "__DONE__", ""))
        try { translatorThread.join(5000) } catch (_: Exception) {}

        if (!sttOk) {
            onProgress(Progress("error", 0, "STT 실패"))
            return null
        }

        val finalSegments = synchronized(lock) { collected.toList() }
        LogBus.log(TAG, "완료: ${finalSegments.size} 세그먼트")

        val srtFile = File(AudioPaths.subtitleDir(context), "test_${targetLang}.srt")
        writeSrt(srtFile, finalSegments)
        LogBus.log(TAG, "SRT: ${srtFile.absolutePath}")

        if (cacheSourceKey != null) {
            SubtitleCache.write(context, cacheSourceKey, targetLang, srtFile.readText())
        }

        onProgress(Progress("done", 100, "완료: ${finalSegments.size} 세그먼트"))
        return srtFile
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
