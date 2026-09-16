package com.mdkdw1.splayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object LogBus {
    private const val MAX = 200

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun log(tag: String, msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$time][$tag] $msg"
        val cur = _lines.value.toMutableList()
        cur.add(line)
        while (cur.size > MAX) cur.removeAt(0)
        _lines.value = cur
        android.util.Log.d("SPlayer", line)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
