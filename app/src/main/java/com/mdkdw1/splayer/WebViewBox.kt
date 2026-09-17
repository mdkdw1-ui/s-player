package com.mdkdw1.splayer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray

class JsBridge(
    private val onCaption: (String) -> Unit,
    private val onAudioChunk: (FloatArray) -> Unit,
    private val onVideoFound: (Boolean) -> Unit,
    private val onUrlChanged: (String) -> Unit,
    private val onLog: (String) -> Unit
) {
    @JavascriptInterface fun onCaption(text: String) { onCaption(text) }

    @JavascriptInterface
    fun onAudio(dataJson: String) {
        val arr = JSONArray(dataJson)
        val out = FloatArray(arr.length())
        for (i in 0 until arr.length()) out[i] = arr.getDouble(i).toFloat()
        onAudioChunk(out)
    }

    @JavascriptInterface fun onVideoFound(found: Boolean) { onVideoFound(found) }
    @JavascriptInterface fun onUrlChanged(url: String) { onUrlChanged(url) }
    @JavascriptInterface fun onLog(msg: String) { onLog(msg) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBox(
    loadUrl: String,
    speed: Float,
    onCaption: (String) -> Unit,
    onAudioChunk: (FloatArray) -> Unit,
    onVideoFound: (Boolean) -> Unit,
    onUrlChanged: (String) -> Unit,
    onLog: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadUrl) {
        webView?.let { wv -> if (wv.url != loadUrl) wv.loadUrl(loadUrl) }
    }

    LaunchedEffect(speed, webView) {
        webView?.applyPlaybackSpeed(speed)
    }

    LaunchedEffect(pageGeneration, webView) {
        val wv = webView ?: return@LaunchedEffect
        repeat(10) { i ->
            delay(1000)
            try {
                wv.injectAudioCaptureScript()
                onLog("re-inject #${i + 1}")
            } catch (e: Exception) {
                onLog("re-inject fail: ${e.message}")
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // ===== 성능 최적화 =====
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false

                        // 캐시 적극 활용
                        cacheMode = WebSettings.LOAD_DEFAULT

                        // 뷰포트/스케일
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        builtInZoomControls = false
                        displayZoomControls = false

                        // 렌더링 최적화
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setRendererPriorityPolicy(
                                WebView.RENDERER_PRIORITY_IMPORTANT, false
                            )
                        }

                        // 이미지
                        loadsImagesAutomatically = true
                        blockNetworkImage = false

                        // 폰트 부스트 방지
                        textZoom = 100

                        // JS 창
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)

                        // 오프라인 캐시 (deprecated이지만 여전히 유효)
                        @Suppress("DEPRECATION")
                        allowFileAccess = false

                        // 쿠키
                        @Suppress("DEPRECATION")
                        acceptThirdPartyCookies(this@apply)

                        // 데스크톱 UA 쓰지 않고 **모바일 Chrome UA** 로 통일
                        // (구글 로그인, YouTube 최적화에 유리)
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; SM-S908B) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    }

                    // 쿠키 매니저
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }

                    // 하드웨어 가속 (View 레벨)
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onConsoleMessage(
                            consoleMessage: android.webkit.ConsoleMessage?
                        ): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            if (msg.contains("[SPlayer]")) onLog("console: $msg")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            url?.let { onUrlChanged(it) }
                            onLog("page started: $url")
                            pageGeneration++
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            url?.let { onUrlChanged(it) }
                            onLog("page finished: $url")
                            view?.injectAudioCaptureScript()
                        }

                        override fun onReceivedError(
                            view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            onLog("web error $errorCode: $description @ $failingUrl")
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString() ?: return false
                            onUrlChanged(target)
                            view?.loadUrl(target)
                            return true
                        }
                    }

                    addJavascriptInterface(
                        JsBridge(onCaption, onAudioChunk, onVideoFound, onUrlChanged, onLog),
                        "AndroidBridge"
                    )

                    // DNS 프리페치
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // 이미 on 이지만 명시
                        settings.isSafeBrowsingEnabled = false  // 세이프 브라우징 off (속도 ↑, 보안 ↓)
                    }

                    loadUrl(loadUrl)
                    webView = this
                    onWebViewReady(this)
                }
            }
        )

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
            )
        }
    }
}
