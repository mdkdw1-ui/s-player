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
        LogBus.log(TAG, "=== START uri=$sourceUri")

        // ---------- 1. 원본 파일 복사 ----------
        onProgress(Progress("copy", 0, "파일 복사 중..."))
        LogBus.log(TAG, "[1] 복사 시작")

        val inputFile = copyToCache(context, sourceUri)
        if (inputFile == null) {
            LogBus.log(TAG, "[1] 복사 실패")
            onProgress(Progress("error", 0, "파일 복사 실패"))
            return@withContext null
        }
        LogBus.log(TAG, "[1] 복사 완료: ${inputFile.absolutePath} (${inputFile.length()} bytes)")
        onProgress(Progress("copy", 100, "복사 완료"))

        // ---------- 2. WAV 디코딩 ----------
        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))
        LogBus.log(TAG, "[2] 디코딩 시작")

        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()

        val decodeOk = try {
            AudioDecoder.decodeToWav(inputFile, wavFile)
        } catch (e: Exception) {
            LogBus.log(TAG, "[2] 디코딩 예외: ${e.message}")
            false
        }

        if (!decodeOk || !wavFile.exists()) {
            LogBus.log(TAG, "[2] 디코딩 실패 (ok=$decodeOk, exists=${wavFile.exists()})")
            onProgress(Progress("error", 0, "디코딩 실패"))
            return@withContext null
        }
        LogBus.log(TAG, "[2] 디코딩 완료: ${wavFile.absolutePath} (${wavFile.length()} bytes)")
        onProgress(Progress("decode", 100, "디코딩 완료"))

        // ---------- 3. Whisper STT ----------
        onProgress(Progress("stt", 0, "Whisper 모델 로드 중..."))
        LogBus.log(TAG, "[3] 모델 확인")

        if (!WhisperModelDownloader.isInstalled(context, model)) {
            LogBus.log(TAG, "[3] 모델 미설치: ${model.id}")
            onProgress(Progress("error", 0, "모델 미설치"))
            return@withContext null
        }
        val modelPath = WhisperModelDownloader.modelFile(context, model).absolutePath
        LogBus.log(TAG, "[3] 모델 경로: $modelPath")

        val collected = mutableListOf<Segment>()
        val translator = GoogleTranslator()

        // 번역은 별도 스레드에서 순차 처리
        val translateQueue = java.util.concurrent.LinkedBlockingQueue<Segment>()
        val segmentsLock = Object()
        var translatorThread: Thread? = null

        translatorThread = Thread {
            while (true) {
                val seg = try { translateQueue.take() } catch (e: Exception) { break }
                if (seg.original == "__DONE__") break

                val translated = try {
                    if (targetLang == sourceLang) seg.original
                    else kotlinx.coroutines.runBlocking {
                        translator.translate(seg.original, targetLang, sourceLang)
                    }
                } catch (e: Exception) {
                    LogBus.log(TAG, "번역 예외: ${e.message}")
                    ""
                }
                val finalSeg = seg.copy(translated = translated)
                synchronized(segmentsLock) { collected.add(finalSeg) }
                onSegment(finalSeg)
                LogBus.log(TAG, "SEG [${finalSeg.startMs}ms] ${finalSeg.original.take(40)}")
            }
        }
        translatorThread.start()

        LogBus.log(TAG, "[3] Whisper 시작 (lang=$sourceLang)")
        onProgress(Progress("stt", 0, "음성 인식 중..."))

        val sttOk = try {
            WhisperBridge.transcribe(
                modelPath = modelPath,
                wavPath = wavFile.absolutePath,
                language = sourceLang,
                threads = 4,
                callback = object : WhisperBridge.SegmentCallback {
                    override fun onSegment(startMs: Long, endMs: Long, text: String) {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) return
                        // 번역 큐에 넣고 즉시 리턴 (Whisper 스레드 블록 방지)
                        translateQueue.put(Segment(startMs, endMs, trimmed, ""))
                    }

                    override fun onProgress(percent: Int) {
                        onProgress(Progress("stt", percent, "인식 중 $percent%"))
                    }

                    override fun onComplete() {
                        LogBus.log(TAG, "[3] Whisper 완료")
                    }
                }
            )
        } catch (e: Exception) {
            LogBus.log(TAG, "[3] Whisper 예외: ${e.message}")
            false
        }

        // 번역 스레드 종료
        translateQueue.put(Segment(0, 0, "__DONE__", ""))
        try { translatorThread.join(3000) } catch (_: Exception) {}

        if (!sttOk) {
            LogBus.log(TAG, "[3] STT 실패")
            onProgress(Progress("error", 0, "STT 실패"))
            return@withContext null
        }

        val finalSegments = synchronized(segmentsLock) { collected.toList() }
        LogBus.log(TAG, "[3] 완료: ${finalSegments.size} 세그먼트")

        // ---------- 4. SRT 저장 ----------
        onProgress(Progress("srt", 0, "자막 파일 저장..."))
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
            dest.outputStream().use { output ->
                input.copyTo(output, bufferSize = 128 * 1024)
            }
        }
        dest
    } catch (e: Exception) {
        Log.e(TAG, "복사 실패", e)
        null
    }

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
            sb.append(if (seg.translated.isNotBlank()) seg.translated else seg.original).append('\n')
            sb.append('\n')
        }
        file.writeText(sb.toString())
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val msec = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, msec)
    }
}
