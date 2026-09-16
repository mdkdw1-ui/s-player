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

    /**
     * URL 정규화: 같은 영상이면 같은 키가 나오도록.
     * - YouTube: youtu.be/ID, youtube.com/watch?v=ID, m.youtube.com/watch?v=ID, youtube.com/shorts/ID
     * - 기타: URL 그대로
     */
    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')

        // YouTube videoId 추출
        val ytId = extractYouTubeId(trimmed)
        if (ytId != null) return "yt:$ytId"

        // SoundCloud, Vimeo 등은 URL 그대로
        return trimmed
    }

    private fun extractYouTubeId(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: return null

            when {
                host.contains("youtu.be") -> {
                    // https://youtu.be/VIDEO_ID
                    uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
                }
                host.contains("youtube.com") -> {
                    // /watch?v=VIDEO_ID
                    val v = uri.getQueryParameter("v")
                    if (v != null && v.length == 11) v
                    else {
                        // /shorts/VIDEO_ID, /embed/VIDEO_ID, /v/VIDEO_ID
                        val segs = uri.pathSegments
                        when {
                            segs.size >= 2 && (segs[0] == "shorts" || segs[0] == "embed" || segs[0] == "v") -> segs[1]
                            else -> null
                        }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
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
        val exists = f.exists() && f.length() > 50
        if (exists) Log.i(TAG, "캐시 히트: ${f.name} (${f.length()} bytes)")
        return exists
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
        Log.i(TAG, "캐시 저장: ${f.name} (${content.length} bytes) key=${normalize(sourceKey)}")
        return f
    }

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
            val text = lines.drop(2).joinToString(" ").trim()
            if (text.isEmpty()) continue
            out.add(SubtitlePipeline.Segment(startMs, endMs, "", text))
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
