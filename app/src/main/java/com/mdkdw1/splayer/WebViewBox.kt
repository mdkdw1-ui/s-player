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
    desktopUA: Boolean = false,
    reloadKey: Int = 0,
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

    // UA 모드 변경 시 WebView 재생성 (reload)
    LaunchedEffect(desktopUA, webView) {
        val wv = webView ?: return@LaunchedEffect
        val ua = if (desktopUA) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        if (wv.settings.userAgentString != ua) {
            wv.settings.userAgentString = ua
            onLog("UA 변경: ${if (desktopUA) "데스크톱" else "모바일"}")
            wv.reload()
        }
    }

    // 외부 reload 트리거
    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) {
            webView?.reload()
        }
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
                val wv = WebView(ctx)

                // 쿠키 매니저 설정
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAcceptThirdPartyCookies(wv, true)
                    }
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    textZoom = 100
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)

                    // 모바일 Chrome UA
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; SM-S908B) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }

                wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                wv.webChromeClient = object : WebChromeClient() {
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

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { onUrlChanged(it) }
                        onLog("page started: $url")
                        pageGeneration++

                        // ★ 광고 스크립트 사전 차단 (missav 등)
                        if (url?.contains("missav") == true) {
                            view?.evaluateJavascript(AD_BLOCK_JS, null)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { onUrlChanged(it) }
                        onLog("page finished: $url")
                        view?.injectAudioCaptureScript()
                    }

                    // ★ SPA URL 변경 감지 (YouTube 등)
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        url?.let {
                            onUrlChanged(it)
                            onLog("history: $it")
                        }
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

                        // ===== 광고/팝업 차단 =====
                        if (shouldBlockUrl(target)) {
                            onLog("🚫 차단: ${target.take(80)}")
                            return true  // 로드 차단
                        }

                        onUrlChanged(target)
                        view?.loadUrl(target)
                        return true
                    }
                }

                wv.addJavascriptInterface(
                    JsBridge(onCaption, onAudioChunk, onVideoFound, onUrlChanged, onLog),
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
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
            )
        }
    }
}


/**
 * 광고/팝업/트래커 도메인 차단.
 * missav 등 성인 사이트의 aggressive 광고 대응.
 */
private fun shouldBlockUrl(url: String): Boolean {
    val lower = url.lowercase()

    // 1. 알려진 광고/팝업 패턴
    val blockPatterns = listOf(
        "/pop?url=",              // missav 광고 팝업
        "/popup",
        "/ad/", "/ads/", "/adv/",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "adsterra.com",
        "propellerads.com",
        "hilltopads.net",
        "clickadu.com",
        "onclickads.net",
        "mgid.com",
        "taboola.com",
        "outbrain.com",
        "diffusedpassionquaking",   // 로그에서 확인된 도메인
        "traffic-media.co",
        "tsyndicate.com",
        "ad-maven.com",
        "revcontent.com",
    )

    for (pattern in blockPatterns) {
        if (lower.contains(pattern)) return true
    }

    // 2. missav 도메인 화이트리스트 (그 외는 차단)
    val allowedDomains = listOf(
        "missav.ws",
        "missav01.com",
        "missav.ai",
        "missav123.com",
        "missav.com",
        "mymissav.com",
        "about:blank",
        "data:",
        "blob:",
    )
    for (domain in allowedDomains) {
        if (lower.contains(domain)) return false
    }

    // 3. 정상 사이트 (YouTube, Vimeo 등) 는 허용
    val normalDomains = listOf(
        "youtube.com", "youtu.be",
        "vimeo.com",
        "google.com",
        "w3schools.com",
        "cnn.com",
        "wikipedia.org",
        "soundcloud.com",
    )
    for (domain in normalDomains) {
        if (lower.contains(domain)) return false
    }

    // 4. 그 외 외부 도메인은 차단 (광고 가능성)
    // → 하지만 정상 링크도 있을 수 있으니, http(s) 로 시작하는 외부 도메인만 조건부 차단
    // 일단 로그만 남기고 허용 (필요시 차단으로 변경)
    android.util.Log.d("WebViewBox", "허용: $url")
    return false
}


/**
 * 광고 스크립트 사전 차단 (missav 등 성인 사이트용).
 * - window.open 후킹
 * - 클릭 하이재킹 방지
 * - 광고 overlay 제거
 */
private val AD_BLOCK_JS = """
(function() {
  try {
    // window.open 차단
    window.open = function() {
      console.log('[ADBLOCK] window.open 차단');
      return null;
    };

    // location 변경 후킹 (외부 도메인 이동 차단)
    var origAssign = location.assign;
    var origReplace = location.replace;
    var allowed = ['missav.ws', 'missav01.com', 'missav.ai', 'missav.com'];

    function checkUrl(url) {
      if (!url) return false;
      for (var i = 0; i < allowed.length; i++) {
        if (url.indexOf(allowed[i]) !== -1) return false;
      }
      return true;  // 차단 대상
    }

    try {
      Object.defineProperty(location, 'href', {
        set: function(url) {
          if (checkUrl(url)) {
            console.log('[ADBLOCK] href 이동 차단: ' + url);
          } else {
            window.location.href = url;
          }
        }
      });
    } catch (e) {}

    // 클릭 하이재킹 방지 (a 태그 광고)
    document.addEventListener('click', function(e) {
      var a = e.target.closest && e.target.closest('a');
      if (a && a.href && checkUrl(a.href)) {
        console.log('[ADBLOCK] 링크 차단: ' + a.href);
        e.preventDefault();
        e.stopPropagation();
        return false;
      }
    }, true);

    // 광고 overlay 자동 제거 (주기적)
    setInterval(function() {
      // 일반적인 광고 overlay 셀렉터
      var adSelectors = [
        '[class*="popup"]',
        '[class*="overlay"]',
        '[id*="popup"]',
        '[id*="ad-"]',
        '[class*="ad-wrap"]',
        'iframe[src*="ad"]',
        'iframe[src*="pop"]',
      ];
      adSelectors.forEach(function(sel) {
        document.querySelectorAll(sel).forEach(function(el) {
          if (el.style && !el.__adblockChecked) {
            el.__adblockChecked = true;
            var src = (el.src || '') + (el.href || '');
            for (var i = 0; i < allowed.length; i++) {
              if (src.indexOf(allowed[i]) !== -1) return;  // missav 자체는 유지
            }
            // 3초 후 제거 (깜빡임 방지)
            setTimeout(function() { if (el.parentNode) el.style.display = 'none'; }, 100);
          }
        });
      });
    }, 2000);

    console.log('[ADBLOCK] 활성화');
  } catch (e) {
    console.log('[ADBLOCK] 실패: ' + e);
  }
})();
""".trimIndent()
