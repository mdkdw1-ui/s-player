package com.mdkdw1.splayer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import org.json.JSONArray

class JsBridge(
    private val onCaption: (String) -> Unit,
    private val onAudioChunk: (FloatArray) -> Unit,
    private val onVideoFound: (Boolean) -> Unit,
    private val onUrlChanged: (String) -> Unit
) {
    @JavascriptInterface
    fun onCaption(text: String) {
        onCaption(text)
    }

    @JavascriptInterface
    fun onAudio(dataJson: String) {
        val arr = JSONArray(dataJson)
        val out = FloatArray(arr.length())
        for (i in 0 until arr.length()) out[i] = arr.getDouble(i).toFloat()
        onAudioChunk(out)
    }

    @JavascriptInterface
    fun onVideoFound(found: Boolean) {
        onVideoFound(found)
    }

    @JavascriptInterface
    fun onUrlChanged(url: String) {
        onUrlChanged(url)
    }
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
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // loadUrl 값이 바뀔 때만 실제 로드
    LaunchedEffect(loadUrl) {
        webView?.let { wv ->
            if (wv.url != loadUrl) {
                wv.loadUrl(loadUrl)
            }
        }
    }

    LaunchedEffect(speed, webView) {
        webView?.applyPlaybackSpeed(speed)
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.mediaPlaybackRequiresUserGesture = false
                wv.settings.loadWithOverviewMode = true
                wv.settings.useWideViewPort = true
                wv.settings.userAgentString =
                    wv.settings.userAgentString + " SPlayer/1.0"

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?, url: String?, favicon: Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { onUrlChanged(it) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { onUrlChanged(it) }
                        view?.injectAudioCaptureScript()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val target = request?.url?.toString() ?: return false
                        onUrlChanged(target)
                        view?.loadUrl(target)
                        return true
                    }
                }

                wv.addJavascriptInterface(
                    JsBridge(onCaption, onAudioChunk, onVideoFound, onUrlChanged),
                    "AndroidBridge"
                )

                wv.loadUrl(loadUrl)
                webView = wv
                onWebViewReady(wv)
                wv
            }
        )

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}
