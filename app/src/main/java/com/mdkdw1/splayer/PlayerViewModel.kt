package com.mdkdw1.splayer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class PlayerMode { LOCAL, WEBVIEW }

data class SubtitleCue(
    val original: String = "",
    val translated: String = "",
    val isFinal: Boolean = false
)

data class ModelStatus(
    val language: SttLanguage = SttLanguage.KOREAN,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

data class SttState(
    val running: Boolean = false,
    val stage: String = "",
    val percent: Int = 0,
    val message: String = "",
    val segments: List<SubtitlePipeline.Segment> = emptyList(),
    val srtPath: String? = null
)

data class WhisperModelStatus(
    val model: WhisperModel = WhisperModel.BASE,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    // ----- 기존 상태 -----
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

    private val _rawLevel = MutableStateFlow(0f)
    val rawLevel: StateFlow<Float> = _rawLevel

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

    // ----- STT / Whisper 관련 상태 (신규) -----
    private val _whisperModel = MutableStateFlow(WhisperModelStatus())
    val whisperModel: StateFlow<WhisperModelStatus> = _whisperModel

    private val _sttState = MutableStateFlow(SttState())
    val sttState: StateFlow<SttState> = _sttState

    init {
        refreshModelStatus()
        refreshWhisperStatus()
        LogBus.log("VM", "init")

        AudioCaptureService.onSamples = { samples, rate ->
            pipeline?.push(samples, sampleRate = rate)
        }
        AudioCaptureService.onRawLevel = { _rawLevel.value = it }
        AudioCaptureService.onLevel = { _outLevel.value = it }
    }

    // ================= Whisper 모델 =================

    fun refreshWhisperStatus() {
        val m = _whisperModel.value.model
        val installed = WhisperModelDownloader.isInstalled(getApplication(), m)
        _whisperModel.value = _whisperModel.value.copy(installed = installed, error = null)
        LogBus.log("VM", "whisper ${m.id} installed=$installed")
    }

    fun selectWhisperModel(model: WhisperModel) {
        _whisperModel.value = _whisperModel.value.copy(
            model = model, progress = 0f, error = null
        )
        refreshWhisperStatus()
    }

    fun downloadWhisperModel() {
        val m = _whisperModel.value.model
        if (_whisperModel.value.downloading) return
        _whisperModel.value = _whisperModel.value.copy(
            downloading = true, progress = 0f, error = null
        )
        viewModelScope.launch {
            val ok = WhisperModelDownloader.download(getApplication(), m) { p ->
                _whisperModel.value = _whisperModel.value.copy(progress = p)
            }
            if (ok) {
                _whisperModel.value = _whisperModel.value.copy(
                    downloading = false, installed = true, progress = 1f
                )
            } else {
                _whisperModel.value = _whisperModel.value.copy(
                    downloading = false, error = "다운로드 실패"
                )
            }
        }
    }

    // ================= 로컬 파일 STT =================

    fun runLocalStt(uri: Uri) {
        if (_sttState.value.running) return
        val model = _whisperModel.value.model
        if (!_whisperModel.value.installed) {
            LogBus.log("STT", "모델 미설치")
            return
        }

        _sttState.value = SttState(running = true, stage = "start", percent = 0)

        viewModelScope.launch {
            val srt = SubtitlePipeline.run(
                context = getApplication(),
                sourceUri = uri,
                model = model,
                sourceLang = "en",     // 일단 영어 고정. UI에서 바꾸게 확장 가능
                targetLang = "ko",
                onProgress = { p ->
                    _sttState.value = _sttState.value.copy(
                        stage = p.stage,
                        percent = p.percent,
                        message = p.message
                    )
                    LogBus.log("STT", "${p.stage} ${p.percent}% ${p.message}")
                },
                onSegment = { seg ->
                    _sttState.value = _sttState.value.copy(
                        segments = _sttState.value.segments + seg
                    )
                    LogBus.log("SEG", "[${seg.startMs}ms] ${seg.original.take(40)} → ${seg.translated.take(40)}")
                }
            )

            _sttState.value = _sttState.value.copy(
                running = false,
                srtPath = srt?.absolutePath,
                stage = if (srt != null) "done" else "error"
            )
        }
    }

    fun clearStt() {
        _sttState.value = SttState()
    }

    // ================= 기존 캡처/웹 =================

    private fun refreshModelStatus() {
        val lang = _modelStatus.value.language
        val installed = ModelDownloader.isInstalled(getApplication(), lang)
        _modelStatus.value = _modelStatus.value.copy(installed = installed, error = null)
        if (installed) createPipeline(lang)
    }

    private fun createPipeline(lang: SttLanguage) {
        pipeline?.close()
        pipeline = TranslationPipeline(getApplication(), lang) { original, translated, isFinal ->
            _subtitle.value = SubtitleCue(original, translated, isFinal)
        }
    }

    fun selectLanguage(lang: SttLanguage) {
        _modelStatus.value = _modelStatus.value.copy(language = lang, progress = 0f, error = null)
        refreshModelStatus()
    }

    fun downloadModel() {
        val lang = _modelStatus.value.language
        if (_modelStatus.value.downloading) return
        _modelStatus.value = _modelStatus.value.copy(downloading = true, progress = 0f, error = null)
        viewModelScope.launch {
            val ok = ModelDownloader.download(getApplication(), lang) { p ->
                _modelStatus.value = _modelStatus.value.copy(progress = p)
            }
            if (ok) {
                _modelStatus.value = _modelStatus.value.copy(
                    downloading = false, installed = true, progress = 1f
                )
                createPipeline(lang)
            } else {
                _modelStatus.value = _modelStatus.value.copy(downloading = false, error = "다운로드 실패")
            }
        }
    }

    fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
        _captureOn.value = true
    }

    fun stopCapture() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, AudioCaptureService::class.java))
        _captureOn.value = false
        _rawLevel.value = 0f
        _outLevel.value = 0f
    }

    fun setMode(m: PlayerMode) { _mode.value = m }
    fun setSpeed(s: Float) { _speed.value = s.coerceIn(0.25f, 4.0f) }
    fun updateSubtitle(cue: SubtitleCue) { _subtitle.value = cue }
    fun setVideoUrl(url: String) { _videoUrl.value = url }
    fun setVideoFound(found: Boolean) { _videoFound.value = found }
    fun onUrlInputChange(text: String) { _urlInput.value = text }
    fun onJsLog(msg: String) { LogBus.log("JS", msg) }

    fun navigateToInput() {
        var target = _urlInput.value.trim()
        if (target.isEmpty()) return
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = if (target.contains(".") && !target.contains(" ")) "https://$target"
            else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
        }
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
    }
}
