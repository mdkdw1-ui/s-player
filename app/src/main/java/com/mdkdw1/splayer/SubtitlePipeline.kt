package com.mdkdw1.splayer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mdkdw1.splayer.audio.AudioDecoder
import com.mdkdw1.splayer.audio.AudioPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 로컬 미디어 파일 → 자막(SRT) 파이프라인.
 *
 * 1. 파일 복사 (URI → cache)
 * 2. AudioDecoder 로 WAV 변환 (16kHz mono)
 * 3. WhisperBridge 로 세그먼트 추출
 * 4. 각 세그먼트 번역
 * 5. SRT 파일로 저장
 */
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

    /**
     * @param context 앱 컨텍스트
     * @param sourceUri 로컬 파일 URI (content:// 또는 file://)
     * @param model Whisper 모델
     * @param sourceLang 원본 언어 (Whisper에 전달, "auto" 가능)
     * @param targetLang 번역 타깃 ("ko")
     * @param onProgress 진행률 콜백
     * @param onSegment 세그먼트마다 콜백
     */
    suspend fun run(
        context: Context,
        sourceUri: Uri,
        model: WhisperModel,
        sourceLang: String = "auto",
        targetLang: String = "ko",
        onProgress: (Progress) -> Unit,
        onSegment: (Segment) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        // ---------- 1. 원본 파일 복사 ----------
        onProgress(Progress("copy", 0, "파일 복사 중..."))

        val inputFile = copyToCache(context, sourceUri)
        if (inputFile == null) {
            onProgress(Progress("error", 0, "파일 복사 실패"))
            return@withContext null
        }
        Log.i(TAG, "입력 파일: ${inputFile.absolutePath}, ${inputFile.length()} bytes")
        onProgress(Progress("copy", 100, "복사 완료 (${inputFile.length() / 1024 / 1024}MB)"))

        // ---------- 2. WAV 디코딩 ----------
        onProgress(Progress("decode", 0, "오디오 디코딩 중..."))

        val wavFile = AudioPaths.tempWav(context, "test")
        if (wavFile.exists()) wavFile.delete()

        val decodeOk = AudioDecoder.decodeToWav(inputFile, wavFile)
        if (!decodeOk || !wavFile.exists()) {
            onProgress(Progress("error", 0, "디코딩 실패"))
            return@withContext null
        }
        Log.i(TAG, "WAV: ${wavFile.absolutePath}, ${wavFile.length()} bytes")
        onProgress(Progress("decode", 100, "디코딩 완료 (${wavFile.length() / 1024 / 1024}MB)"))

        // ---------- 3. Whisper STT ----------
        onProgress(Progress("stt", 0, "음성 인식 준비..."))

        if (!WhisperModelDownloader.isInstalled(context, model)) {
            onProgress(Progress("error", 0, "모델 미설치: ${model.displayName}"))
            return@withContext null
        }
        val modelPath = WhisperModelDownloader.modelFile(context, model).absolutePath
        Log.i(TAG, "모델: $modelPath")

        // 세그먼트 수집
        val collected = mutableListOf<Segment>()
        val translator = GoogleTranslator()

        val sttOk = WhisperBridge.transcribe(
            modelPath = modelPath,
            wavPath = wavFile.absolutePath,
            language = sourceLang,
            threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
            callback = object : WhisperBridge.SegmentCallback {

                override fun onSegment(startMs: Long, endMs: Long, text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) return

                    // 번역은 여기서 동기로 (Whisper 스레드 안). suspend 불가라 blocking.
                    // 개선: 큐에 넣고 별도 스레드에서 처리. 일단 그냥 진행.
                    val translated = try {
                        kotlinx.coroutines.runBlocking {
                            if (targetLang == sourceLang) trimmed
                            else translator.translate(trimmed, targetLang, sourceLang)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "번역 실패", e)
                        ""
                    }

                    val seg = Segment(startMs, endMs, trimmed, translated)
                    collected.add(seg)
                    onSegment(seg)
                }

                override fun onProgress(percent: Int) {
                    onProgress(Progress("stt", percent, "인식 중... $percent%"))
                }

                override fun onComplete() {
                    onProgress(Progress("stt", 100, "인식 완료 (${collected.size}개 세그먼트)"))
                }
            }
        )

        if (!sttOk) {
            onProgress(Progress("error", 0, "STT 실패"))
            return@withContext null
        }

        // ---------- 4. SRT 저장 ----------
        onProgress(Progress("srt", 0, "자막 파일 저장..."))

        val srtFile = File(AudioPaths.subtitleDir(context), "test_${targetLang}.srt")
        writeSrt(srtFile, collected)

        Log.i(TAG, "SRT: ${srtFile.absolutePath}")
        onProgress(Progress("done", 100, "완료: ${srtFile.absolutePath}"))

        // 임시 WAV 정리 (선택)
        // wavFile.delete()

        srtFile
    }

    // ---------- 유틸 ----------

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
    } catch (e: Exception) {
        null
    }

    private fun writeSrt(file: File, segments: List<Segment>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            sb.append(i + 1).append('\n')
            sb.append(formatTime(seg.startMs)).append(" --> ").append(formatTime(seg.endMs)).append('\n')
            // 번역이 있으면 번역, 없으면 원문
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
