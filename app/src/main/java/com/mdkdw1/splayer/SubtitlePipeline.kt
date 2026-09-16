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

    suspend fun run(
        context: Context,
        sourceUri: Uri,
        model: WhisperModel,
        sourceLang: String = "auto",
        targetLang: String = "ko",
        onProgress: (Progress) -> Unit,
        onSegment: (Segment) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        LogBus.log(TAG, "=== START")

        onProgress(Progress("copy", 0, "파일 복사 중..."))
        val inputFile = copyToCache(context, sourceUri)
        if (inputFile == null) {
            onProgress(Progress("error", 0, "파일 복사 실패")); return@withContext null
        }
        LogBus.log(TAG, "[1] 복사: ${inputFile.length()} bytes")
        onProgress(Progress("copy", 100, "복사 완료"))

        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()
        val decodeOk = try { AudioDecoder.decodeToWav(inputFile, wavFile) } catch (e: Exception) {
            LogBus.log(TAG, "[2] 예외: ${e.message}"); false
        }
        if (!decodeOk || !wavFile.exists()) {
            LogBus.log(TAG, "[2] 실패")
            onProgress(Progress("error", 0, "디코딩 실패")); return@withContext null
        }
        LogBus.log(TAG, "[2] WAV: ${wavFile.length()} bytes")
        onProgress(Progress("decode", 100, "디코딩 완료"))

        if (!WhisperModelDownloader.isInstalled(context, model)) {
            LogBus.log(TAG, "[3] 모델 미설치")
            onProgress(Progress("error", 0, "모델 미설치")); return@withContext null
        }
        val modelPath = WhisperModelDownloader.modelFile(context, model).absolutePath
        LogBus.log(TAG, "[3] 모델: $modelPath")

        val collected = mutableListOf<Segment>()
        val translator = GoogleTranslator()
        val translateQueue = java.util.concurrent.LinkedBlockingQueue<Segment>()
        val segmentsLock = Object()

        val translatorThread = Thread {
            while (true) {
                val seg = try { translateQueue.take() } catch (e: Exception) { break }
                if (seg.original == "__DONE__") break
                val translated = try {
                    if (targetLang == sourceLang) seg.original
                    else kotlinx.coroutines.runBlocking {
                        translator.translate(seg.original, targetLang, sourceLang)
                    }
                } catch (e: Exception) { "" }
                val finalSeg = seg.copy(translated = translated)
                synchronized(segmentsLock) { collected.add(finalSeg) }
                onSegment(finalSeg)
                LogBus.log(TAG, "SEG [${finalSeg.startMs}ms] ${finalSeg.original.take(40)}")
            }
        }
        translatorThread.start()

        onProgress(Progress("stt", 0, "음성 인식 중..."))

        val sttOk = try {
            WhisperBridge.transcribe(
                modelPath = modelPath,
                wavPath = wavFile.absolutePath,
                language = sourceLang,
                threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
                callback = object : WhisperBridge.SegmentCallback {
                    override fun onSegment(startMs: Long, endMs: Long, text: String) {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) return
                        translateQueue.put(Segment(startMs, endMs, trimmed, ""))
                    }
                    override fun onProgress(percent: Int) {
                        onProgress(Progress("stt", percent, "인식 중 $percent%"))
                    }
                    override fun onComplete() {
                        LogBus.log(TAG, "[3] Whisper 완료 콜백")
                    }
                    override fun onLog(msg: String) {
                        LogBus.log("JNI", msg)
                    }
                }
            )
        } catch (e: Exception) {
            LogBus.log(TAG, "[3] 예외: ${e.message}"); false
        }

        translateQueue.put(Segment(0, 0, "__DONE__", ""))
        try { translatorThread.join(3000) } catch (_: Exception) {}

        if (!sttOk) {
            LogBus.log(TAG, "[3] STT 실패")
            onProgress(Progress("error", 0, "STT 실패")); return@withContext null
        }

        val finalSegments = synchronized(segmentsLock) { collected.toList() }
        LogBus.log(TAG, "[3] 완료: ${finalSegments.size} 세그먼트")

        onProgress(Progress("srt", 0, "자막 저장..."))
        val srtFile = File(AudioPaths.subtitleDir(context), "test_${targetLang}.srt")
        writeSrt(srtFile, finalSegments)
        LogBus.log(TAG, "[4] SRT: ${srtFile.absolutePath}")

        onProgress(Progress("done", 100, "완료: ${finalSegments.size} 세그먼트"))
        srtFile
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
