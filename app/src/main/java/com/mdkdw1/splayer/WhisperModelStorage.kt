package com.mdkdw1.splayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 공용 Download/SPlayer/models 폴더를 SAF 로 관리.
 * - 사용자가 한 번 폴더 지정 (ActivityResultContracts.OpenDocumentTree)
 * - takePersistableUriPermission 으로 영구 접근
 * - 이후 DocumentFile 로 파일 읽기/쓰기
 */
object WhisperModelStorage {

    private const val TAG = "WhisperModelStorage"
    private const val PREFS = "splayer_prefs"
    private const val KEY_TREE_URI = "model_tree_uri"

    /** 저장된 폴더 URI (없으면 null) */
    fun getSavedTreeUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_TREE_URI, null) ?: return null
        return try { Uri.parse(s) } catch (e: Exception) { null }
    }

    /** 사용자가 폴더 선택 후 저장 */
    fun saveTreeUri(context: Context, uri: Uri) {
        try {
            // 영구 권한
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.e(TAG, "takePersistableUriPermission 실패", e)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
        LogBus.log(TAG, "폴더 저장: $uri")
    }

    /** 폴더 초기화 */
    fun clearTreeUri(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TREE_URI).apply()
    }

    /** 폴더가 지정되었고 접근 가능한지 */
    fun isFolderReady(context: Context): Boolean {
        val uri = getSavedTreeUri(context) ?: return false
        return try {
            val df = DocumentFile.fromTreeUri(context, uri) ?: return false
            df.exists() && df.isDirectory && df.canWrite()
        } catch (e: Exception) { false }
    }

    /** 폴더 표시 이름 (디버그용) */
    fun folderDisplayName(context: Context): String {
        val uri = getSavedTreeUri(context) ?: return "(미지정)"
        return try {
            DocumentFile.fromTreeUri(context, uri)?.name ?: uri.toString()
        } catch (e: Exception) { uri.toString() }
    }

    /** 모델 파일 DocumentFile */
    fun getModelFile(context: Context, model: WhisperModel): DocumentFile? {
        val uri = getSavedTreeUri(context) ?: return null
        val root = try { DocumentFile.fromTreeUri(context, uri) } ?: return null

        // models 서브폴더 (없으면 생성)
        var modelsDir = root.findFile("models")
        if (modelsDir == null || !modelsDir.isDirectory) {
            modelsDir = root.createDirectory("models") ?: return null
        }

        val fileName = "ggml-${model.id}.bin"
        var f = modelsDir.findFile(fileName)
        if (f == null) {
            f = modelsDir.createFile("application/octet-stream", fileName)
        }
        return f
    }

    /** models 폴더 반환 (없으면 생성) */
    fun getModelsDir(context: Context, createIfMissing: Boolean = true): DocumentFile? {
        val uri = getSavedTreeUri(context) ?: return null
        val root = try { DocumentFile.fromTreeUri(context, uri) } ?: return null
        var modelsDir = root.findFile("models")
        if (modelsDir == null && createIfMissing) {
            modelsDir = root.createDirectory("models")
        }
        return modelsDir
    }

    /** 모델 존재 확인 */
    fun exists(context: Context, model: WhisperModel): Boolean {
        val dir = getModelsDir(context, createIfMissing = false) ?: return false
        val fileName = "ggml-${model.id}.bin"
        val f = dir.findFile(fileName) ?: return false
        return f.exists() && f.length() > 1_000_000
    }

    /** 모델 파일 크기 */
    fun size(context: Context, model: WhisperModel): Long {
        val dir = getModelsDir(context, createIfMissing = false) ?: return 0
        return dir.findFile("ggml-${model.id}.bin")?.length() ?: 0
    }

    /**
     * 모델을 임시 파일로 복사 (JNI 가 file path 를 요구하므로).
     * 캐시 폴더에 저장. 이미 있으면 재사용.
     */
    fun copyToCache(context: Context, model: WhisperModel): File? {
        val target = File(context.cacheDir, "ggml-${model.id}.bin")
        // 이미 있고 크기가 맞으면 재사용
        val expectedSize = size(context, model)
        if (target.exists() && expectedSize > 0 && target.length() == expectedSize) {
            return target
        }

        val doc = getModelFile(context, model) ?: return null
        if (!doc.exists() || doc.length() < 1_000_000) return null

        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }
            LogBus.log(TAG, "캐시 복사 완료: ${target.absolutePath} (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            LogBus.log(TAG, "캐시 복사 실패: ${e.message}")
            null
        }
    }
}
