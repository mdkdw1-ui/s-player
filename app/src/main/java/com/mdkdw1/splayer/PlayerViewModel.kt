package com.mdkdw1.splayer

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlayerMode { LOCAL, WEBVIEW }

data class SubtitleCue(
    val original: String = "",
    val translated: String = ""
)

class PlayerViewModel : ViewModel() {

    private val _mode = MutableStateFlow(PlayerMode.LOCAL)
    val mode: StateFlow<PlayerMode> = _mode

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    private val _subtitle = MutableStateFlow(SubtitleCue())
    val subtitle: StateFlow<SubtitleCue> = _subtitle

    private val _videoUrl = MutableStateFlow(
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    )
    val videoUrl: StateFlow<String> = _videoUrl

    private val _webUrl = MutableStateFlow("https://www.w3schools.com/html/html5_video.asp")
    val webUrl: StateFlow<String> = _webUrl

    fun setMode(m: PlayerMode) { _mode.value = m }
    fun setSpeed(s: Float) { _speed.value = s.coerceIn(0.25f, 4.0f) }
    fun updateSubtitle(cue: SubtitleCue) { _subtitle.value = cue }
    fun setVideoUrl(url: String) { _videoUrl.value = url }
    fun setWebUrl(url: String) { _webUrl.value = url }
}
