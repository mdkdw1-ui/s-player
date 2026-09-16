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

    private val _mode = MutableStateFlow(PlayerMode.WEBVIEW)
    val mode: StateFlow<PlayerMode> = _mode

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    private val _subtitle = MutableStateFlow(SubtitleCue())
    val subtitle: StateFlow<SubtitleCue> = _subtitle

    private val _videoFound = MutableStateFlow(false)
    val videoFound: StateFlow<Boolean> = _videoFound

    private val _videoUrl = MutableStateFlow(
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    )
    val videoUrl: StateFlow<String> = _videoUrl

    // 현재 WebView 가 실제로 로드한 URL (주소창 표시용)
    private val _currentUrl = MutableStateFlow("https://www.w3schools.com/html/html5_video.asp")
    val currentUrl: StateFlow<String> = _currentUrl

    // 주소창에 입력 중인 텍스트
    private val _urlInput = MutableStateFlow(_currentUrl.value)
    val urlInput: StateFlow<String> = _urlInput

    // 실제로 loadUrl 을 트리거하는 URL (중복 로드 방지용)
    private val _loadUrl = MutableStateFlow(_currentUrl.value)
    val loadUrl: StateFlow<String> = _loadUrl

    private val _webUrl = MutableStateFlow("https://www.w3schools.com/html/html5_video.asp")
    val webUrl: StateFlow<String> = _webUrl

    fun setMode(m: PlayerMode) { _mode.value = m }
    fun setSpeed(s: Float) { _speed.value = s.coerceIn(0.25f, 4.0f) }
    fun updateSubtitle(cue: SubtitleCue) { _subtitle.value = cue }
    fun setVideoUrl(url: String) { _videoUrl.value = url }
    fun setWebUrl(url: String) { _webUrl.value = url }
    fun setVideoFound(found: Boolean) { _videoFound.value = found }

    fun onUrlInputChange(text: String) {
        _urlInput.value = text
    }

    /** 사용자가 주소창에서 엔터를 눌렀을 때 */
    fun navigateToInput() {
        var target = _urlInput.value.trim()
        if (target.isEmpty()) return
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = if (target.contains(".") && !target.contains(" ")) {
                "https://$target"
            } else {
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
            }
        }
        _loadUrl.value = target
        _currentUrl.value = target
        _urlInput.value = target
    }

    /** WebView 가 페이지를 로드할 때마다 호출 */
    fun onWebViewUrlChanged(url: String) {
        _currentUrl.value = url
        _urlInput.value = url
    }

    fun onAudioChunk(samples: FloatArray) {
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = kotlin.math.sqrt(sum / samples.size)
        if (rms > 0.01) {
            updateSubtitle(
                SubtitleCue(
                    original = "[audio rms=%.3f]".format(rms),
                    translated = "오디오 캡처 동작 중"
                )
            )
        }
    }
}
