package com.mdkdw1.splayer

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class PlayerMode { LOCAL, WEBVIEW }

data class SubtitleCue(
    val original: String = "",
    val translated: String = ""
)

data class ModelStatus(
    val language: SttLanguage = SttLanguage.KOREAN,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _mode = MutableStateFlow(PlayerMode.WEBVIEW)
    val mode: StateFlow<PlayerMode> = _mode

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    private val _subtitle = MutableStateFlow(SubtitleCue())
    val subtitle: StateFlow<SubtitleCue> = _subtitle

    private val _videoFound = MutableStateFlow(false)
    val videoFound: StateFlow<Boolean> = _videoFound

    private val _modelStatus = MutableStateFlow(ModelStatus())
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private val _captureOn = MutableStateFlow(false)
    val captureOn: StateFlow<Boolean> = _captureOn

    // 원본 RMS (증폭 전). UI에서 "볼륨 낮음" 경고에 사용
    private val _rawLevel = MutableStateFlow(0f)
    val rawLevel: StateFlow<Float> = _rawLevel

    // 증폭 후 RMS (STT에 들어가는 신호 세기)
    private val _outLevel = MutableStateFlow(0f)
    val outLevel: StateFlow<Float> = _outLevel

    private val _videoUrl = MutableStateFlow(
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    )
    val videoUrl: StateFlow<String> = _videoUrl

    private val _currentUrl = MutableStateFlow("https://edition.cnn.com/")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _urlInput = MutableStateFlow(_currentUrl.value)
    val urlInput: StateFlow<String> = _urlInput

    private val _loadUrl = MutableStateFlow(_currentUrl.value)
    val loadUrl: StateFlow<String> = _loadUrl

    private var pipeline: TranslationPipeline? = null

    init {
        refreshModelStatus()
        LogBus.log("VM", "init")

        AudioCaptureService.onSamples = { samples, rate ->
            pipeline?.push(samples, sampleRate = rate)
        }
        AudioCaptureService.onRawLevel = { rms ->
            _rawLevel.value = rms
        }
        AudioCaptureService.onLevel = { rms ->
            _outLevel.value = rms
        }
    }

    private fun refreshModelStatus() {
        val lang = _modelStatus.value.language
        val installed = ModelDownloader.isInstalled(getApplication(), lang)
        LogBus.log("VM", "model ${lang.code} installed=$installed")
        _modelStatus.value = _modelStatus.value.copy(installed = installed, error = null)
        if (installed) createPipeline(lang)
    }

    private fun createPipeline(lang: SttLanguage) {
        pipeline?.close()
        LogBus.log("VM", "create pipeline lang=${lang.code}")
        pipeline = TranslationPipeline(getApplication(), lang) { original, translated ->
            LogBus.log("RESULT", "orig=${original.take(40)} / trans=${translated.take(40)}")
            _subtitle.value = SubtitleCue(original = original, translated = translated)
        }
    }

    fun selectLanguage(lang: SttLanguage) {
        _modelStatus.value = _modelStatus.value.copy(
            language = lang, progress = 0f, error = null
        )
        refreshModelStatus()
    }

    fun downloadModel() {
        val lang = _modelStatus.value.language
        if (_modelStatus.value.downloading) return
        LogBus.log("VM", "download start ${lang.code}")
        _modelStatus.value = _modelStatus.value.copy(
            downloading = true, progress = 0f, error = null
        )
        viewModelScope.launch {
            val ok = ModelDownloader.download(getApplication(), lang) { p ->
                _modelStatus.value = _modelStatus.value.copy(progress = p)
            }
            LogBus.log("VM", "download done ok=$ok")
            if (ok) {
                _modelStatus.value = _modelStatus.value.copy(
                    downloading = false, installed = true, progress = 1f
                )
                createPipeline(lang)
            } else {
                _modelStatus.value = _modelStatus.value.copy(
                    downloading = false, error = "다운로드 실패"
                )
            }
        }
    }

    fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            LogBus.log("CAP", "Android 10 미만은 지원 안 함")
            return
        }
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        _captureOn.value = true
        LogBus.log("CAP", "start service")
    }

    fun stopCapture() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, AudioCaptureService::class.java))
        _captureOn.value = false
        AudioCaptureService.onSamples = null
        AudioCaptureService.onRawLevel = null
        AudioCaptureService.onLevel = null
        _rawLevel.value = 0f
        _outLevel.value = 0f
        LogBus.log("CAP", "stop service")
    }

    fun setMode(m: PlayerMode) { _mode.value = m }
    fun setSpeed(s: Float) { _speed.value = s.coerceIn(0.25f, 4.0f) }
    fun updateSubtitle(cue: SubtitleCue) { _subtitle.value = cue }
    fun setVideoUrl(url: String) { _videoUrl.value = url }
    fun setVideoFound(found: Boolean) {
        LogBus.log("VM", "videoFound=$found")
        _videoFound.value = found
    }

    fun onUrlInputChange(text: String) { _urlInput.value = text }

    fun onJsLog(msg: String) { LogBus.log("JS", msg) }

    fun navigateToInput() {
        var target = _urlInput.value.trim()
        if (target.isEmpty()) return
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = if (target.contains(".") && !target.contains(" ")) "https://$target"
            else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
        }
        LogBus.log("VM", "navigate $target")
        _loadUrl.value = target
        _currentUrl.value = target
        _urlInput.value = target
        pipeline?.reset()
    }

    fun onWebViewUrlChanged(url: String) {
        _currentUrl.value = url
        _urlInput.value = url
    }

    fun onAudioChunk(samples: FloatArray) {
        pipeline?.push(samples, sampleRate = 48000)
    }

    override fun onCleared() {
        super.onCleared()
        pipeline?.close()
        pipeline = null
        AudioCaptureService.onSamples = null
        AudioCaptureService.onRawLevel = null
        AudioCaptureService.onLevel = null
    }
}
