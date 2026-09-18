package com.mdkdw1.splayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 방문 기록 / 북마크 / UA 모드 저장.
 */
object WebPrefs {

    private const val PREFS = "splayer_web"
    private const val KEY_HISTORY = "history"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_DESKTOP_UA = "desktop_ua"
    private const val MAX_HISTORY = 100

    data class HistoryItem(val url: String, val title: String, val time: Long)
    data class BookmarkItem(val url: String, val title: String)

    // ===== 방문 기록 =====
    fun addHistory(context: Context, url: String, title: String = "") {
        if (url.isBlank() || url.startsWith("about:")) return
        val list = getHistory(context).toMutableList()
        // 중복 제거
        list.removeAll { it.url == url }
        list.add(0, HistoryItem(url, title.ifBlank { extractTitle(url) }, System.currentTimeMillis()))
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        saveHistory(context, list)
    }

    fun getHistory(context: Context): List<HistoryItem> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(o.getString("url"), o.optString("title"), o.optLong("time"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveHistory(context: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("title", it.title)
                put("time", it.time)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    // ===== 북마크 =====
    fun addBookmark(context: Context, url: String, title: String = "") {
        if (url.isBlank()) return
        val list = getBookmarks(context).toMutableList()
        if (list.any { it.url == url }) return
        list.add(0, BookmarkItem(url, title.ifBlank { extractTitle(url) }))
        saveBookmarks(context, list)
    }

    fun removeBookmark(context: Context, url: String) {
        val list = getBookmarks(context).toMutableList()
        list.removeAll { it.url == url }
        saveBookmarks(context, list)
    }

    fun isBookmarked(context: Context, url: String): Boolean =
        getBookmarks(context).any { it.url == url }

    fun getBookmarks(context: Context): List<BookmarkItem> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookmarkItem(o.getString("url"), o.optString("title"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveBookmarks(context: Context, list: List<BookmarkItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("title", it.title)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ===== UA 모드 =====
    fun isDesktopUA(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESKTOP_UA, false)

    fun setDesktopUA(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DESKTOP_UA, enabled).apply()
    }

    // ===== 유틸 =====
    private fun extractTitle(url: String): String {
        return try {
            val host = android.net.Uri.parse(url).host ?: url
            host.removePrefix("www.")
        } catch (e: Exception) { url.take(50) }
    }
}
