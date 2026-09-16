package com.mdkdw1.splayer

import android.content.Context
import android.util.Log
import com.mdkdw1.splayer.audio.AudioPaths
import java.io.File
import java.security.MessageDigest

/**
 * URL → SRT 캐시.
 * - 키: URL 의 SHA-1 (videoId 추출 실패해도 URL 해시로 캐시)
 * - 저장: filesDir/subtitles/cache/{hash}_{targetLang}.srt
 */
object SubtitleCache {

    private const val TAG = "SubtitleCache"

    fun cacheDir(context: Context): File =
        File(AudioPaths.subtitleDir(context), "cache").apply { mkdirs() }

    fun hashKey(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun srtFile(context: Context, sourceKey: String, targetLang: String): File =
        File(cacheDir(context), "${hashKey(sourceKey)}_$targetLang.srt")

    fun exists(context: Context, sourceKey: String, targetLang: String): Boolean {
        val f = srtFile(context, sourceKey, targetLang)
        return f.exists() && f.length() > 50
    }

    fun read(context: Context, sourceKey: String, targetLang: String): String? {
        val f = srtFile(context, sourceKey, targetLang)
        if (!f.exists()) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    fun write(context: Context, sourceKey: String, targetLang: String, content: String): File {
        val f = srtFile(context, sourceKey, targetLang)
        f.parentFile?.mkdirs()
        f.writeText(content)
        Log.i(TAG, "캐시 저장: ${f.absolutePath}, ${content.length} bytes")
        return f
    }

    /** SRT 텍스트를 SubtitlePipeline.Segment 리스트로 파싱 */
    fun parseSrt(srt: String): List<SubtitlePipeline.Segment> {
        val out = mutableListOf<SubtitlePipeline.Segment>()
        val blocks = srt.split(Regex("\n\n+"))
        for (block in blocks) {
            val lines = block.trim().split('\n')
            if (lines.size < 3) continue
            val timeLine = lines[1]
            val m = Regex("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})").find(timeLine) ?: continue
            val (_, sh, sm, ss, sms, _, eh, em, es, ems) = m.destructured
            val startMs = sh.toLong()*3600000 + sm.toLong()*60000 + ss.toLong()*1000 + sms.toLong()
            val endMs   = eh.toLong()*3600000 + em.toLong()*60000 + es.toLong()*1000 + ems.toLong()
            // 번역만 저장되어 있으므로 original 은 빈 문자열, translated 만
            val text = lines.drop(2).joinToString(" ").trim()
            if (text.isEmpty()) continue
            out.add(SubtitlePipeline.Segment(startMs, endMs, "", text))
        }
        return out
    }

    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }
}
