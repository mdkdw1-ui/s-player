package com.mdkdw1.splayer

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class JsBridge(
    private val onSubtitle: (String) -> Unit
) {
    @JavascriptInterface
    fun onCaption(text: String) {
        onSubtitle(text)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBox(
    url: String,
    onCaption: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(url) {
        webView?.loadUrl(url)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                addJavascriptInterface(JsBridge(onCaption), "AndroidBridge")
                loadUrl(url)
                webView = this
            }
        }
    )
}
