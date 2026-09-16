package com.mdkdw1.splayer

import android.content.Context
import android.util.Log
import com.mdkdw1.splayer.audio.AudioPaths
import java.io.File
import java.security.MessageDigest

object SubtitleCache {

    private const val TAG = "SubtitleCache"

    fun cacheDir(context: Context): File =
        File(AudioPaths.subtitleDir(context), "cache").apply { mkdirs() }

    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val ytId = extractYouTubeId(trimmed)
        if (ytId != null) return "yt:$ytId"
        return trimmed
    }

    private fun extractYouTubeId(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: return null
            when {
                host.contains("youtu.be") -> {
                    uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
                }
                host.contains("youtube.com") -> {
                    val v = uri.getQueryParameter("v")
                    if (v != null && v.length == 11) v
                    else {
                        val segs = uri.pathSegments
                        when {
                            segs.size >= 2 && (segs[0] == "shorts" || segs[0] == "embed" || segs[0] == "v") -> segs[1]
                            else -> null
                        }
                    }
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun hashKey(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun srtFile(context: Context, sourceKey: String, targetLang: String): File =
        File(cacheDir(context), "${hashKey(normalize(sourceKey))}_$targetLang.srt")

    fun exists(context: Context, sourceKey: String, targetLang: String): Boolean {
        val f = srtFile(context, sourceKey, targetLang)
        val e = f.exists() && f.length() > 50
        if (e) Log.i(TAG, "캐시 히트: ${f.name} (${f.length()} bytes)")
        return e
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
        Log.i(TAG, "캐시 저장: ${f.name} (${content.length} bytes)")
        return f
    }

    /**
     * SRT 파싱. 예외가 나도 절대 크래시하지 않도록 방어.
     */
    fun parseSrt(srt: String): List<SubtitlePipeline.Segment> {
        val out = mutableListOf<SubtitlePipeline.Segment>()
        try {
            val blocks = srt.split(Regex("\n\\s*\n"))
            for (block in blocks) {
                try {
                    val lines = block.trim().split('\n')
                    if (lines.size < 3) continue

                    // 시간 라인 찾기 (index 1 이 아닐 수도 있음)
                    val timeLine = lines.firstOrNull { it.contains("-->") } ?: continue
                    val m = Regex("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})")
                        .find(timeLine) ?: continue

                    val g = m.groupValues
                    val startMs = g[1].toLong()*3600000 + g[2].toLong()*60000 + g[3].toLong()*1000 + g[4].toLong()
                    val endMs   = g[5].toLong()*3600000 + g[6].toLong()*60000 + g[7].toLong()*1000 + g[8].toLong()

                    // 시간 라인 이후 텍스트만
                    val timeIdx = lines.indexOf(timeLine)
                    val text = lines.drop(timeIdx + 1).joinToString(" ").trim()
                    if (text.isEmpty()) continue

                    out.add(SubtitlePipeline.Segment(startMs, endMs, "", text))
                } catch (e: Exception) {
                    Log.w(TAG, "블록 파싱 실패 (skip): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SRT 파싱 실패", e)
        }
        return out
    }

    fun listAll(context: Context): List<File> =
        cacheDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun delete(context: Context, file: File): Boolean = file.delete()

    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }
}
