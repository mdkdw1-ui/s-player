package com.mdkdw1.splayer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object WhisperModelDownloader {

    private const val TAG = "WhisperModelDL"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    /** 모델 파일 경로 (JNI 용, 캐시에 복사본) */
    fun modelFile(context: Context, model: WhisperModel): File =
        File(context.cacheDir, "ggml-${model.id}.bin")

    /** 모델 설치 여부 (SAF 저장소 확인) */
    fun isInstalled(context: Context, model: WhisperModel): Boolean {
        return WhisperModelStorage.exists(context, model)
    }

    /**
     * 모델 다운로드 → SAF 폴더 (Download/SPlayer/models/) 에 저장
     * 다운로드 완료 후 캐시에도 복사 (JNI 가 file path 요구)
     */
    suspend fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!WhisperModelStorage.isFolderReady(context)) {
                LogBus.log(TAG, "저장 폴더 미지정")
                return@withContext false
            }

            if (WhisperModelStorage.exists(context, model)) {
                LogBus.log(TAG, "이미 존재: ${model.id}")
                // 캐시에도 복사 (없으면)
                WhisperModelStorage.copyToCache(context, model)
                onProgress(1f)
                return@withContext true
            }

            val doc = WhisperModelStorage.getModelFile(context, model)
            if (doc == null) {
                LogBus.log(TAG, "파일 생성 실패")
                return@withContext false
            }

            LogBus.log(TAG, "다운로드 시작: ${model.id} (${model.sizeMb}MB)")

            val req = Request.Builder()
                .url(model.url)
                .header("User-Agent", "SPlayer/1.0")
                .build()

            var success = false
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.log(TAG, "HTTP ${resp.code}")
                    return@use
                }
                val body = resp.body ?: return@use
                val total = body.contentLength()
                var read = 0L

                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
                success = true
            }

            if (!success) return@withContext false

            LogBus.log(TAG, "다운로드 완료: ${doc.length()} bytes")

            // 캐시에 복사 (JNI 용)
            WhisperModelStorage.copyToCache(context, model)

            onProgress(1f)
            true
        } catch (e: Exception) {
            LogBus.log(TAG, "실패: ${e.message}")
            false
        }
    }

    suspend fun delete(context: Context, model: WhisperModel): Boolean =
        withContext(Dispatchers.IO) {
            var ok = false
            // SAF 파일 삭제
            WhisperModelStorage.getModelsDir(context, createIfMissing = false)
                ?.findFile("ggml-${model.id}.bin")?.let { if (it.delete()) ok = true }
            // 캐시 파일 삭제
            val cache = File(context.cacheDir, "ggml-${model.id}.bin")
            if (cache.exists()) cache.delete()
            ok
        }
}
