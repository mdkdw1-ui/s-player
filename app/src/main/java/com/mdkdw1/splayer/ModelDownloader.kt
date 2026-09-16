package com.mdkdw1.splayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** 모델이 이미 설치되어 있는지 */
    fun isInstalled(context: Context, lang: SttLanguage): Boolean {
        val dir = modelDir(context, lang)
        val am = File(dir, "am")
        val conf = File(dir, "conf")
        return dir.exists() && am.exists() && conf.exists()
    }

    fun modelDir(context: Context, lang: SttLanguage): File =
        File(context.filesDir, "vosk/${lang.code}")

    /**
     * 모델 다운로드 + 압축 해제.
     * @param onProgress 0.0 ~ 1.0
     */
    suspend fun download(
        context: Context,
        lang: SttLanguage,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetDir = modelDir(context, lang)
            if (isInstalled(context, lang)) {
                onProgress(1f)
                return@withContext true
            }

            // 임시 파일
            val tmpZip = File(context.cacheDir, "${lang.code}.zip")
            if (tmpZip.exists()) tmpZip.delete()

            val req = Request.Builder().url(lang.modelUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "다운로드 실패 ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength()
                var read = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmpZip).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total.toFloat())
                        }
                    }
                }
            }

            // 압축 해제
            targetDir.mkdirs()
            unzip(tmpZip, targetDir.parentFile!!)
            tmpZip.delete()

            // 압축 해제 결과: targetDir 안에 모델 폴더가 있음 → 이동
            val extracted = File(targetDir.parentFile, lang.modelDirName)
            if (extracted.exists() && extracted.absolutePath != targetDir.absolutePath) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                extracted.renameTo(targetDir)
            }

            onProgress(1f)
            Log.i(TAG, "설치 완료: ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "다운로드/해제 실패", e)
            false
        }
    }

    private fun unzip(zip: File, destDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val canonicalDest = destDir.canonicalPath
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // zip slip 방지
                if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                    throw SecurityException("zip slip: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
