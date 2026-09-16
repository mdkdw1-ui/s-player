package com.mdkdw1.splayer

import android.webkit.WebView

fun WebView.injectAudioCaptureScript() {
    val js = """
    (function() {
      if (window.__splayerInstalled) return;
      window.__splayerInstalled = true;

      var ctx = null;
      var sourceNode = null;
      var processor = null;

      function log(msg) {
        try { AndroidBridge.onLog('JS: ' + msg); } catch (e) {}
        console.log('[SPlayer] ' + msg);
      }

      function notifyFound(found) {
        try { AndroidBridge.onVideoFound(found); } catch (e) {}
      }

      function tryAttach(video) {
        if (!video || video.__splayerAttached) return false;
        video.__splayerAttached = true;
        try {
          if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
          if (ctx.state === 'suspended') ctx.resume();
          sourceNode = ctx.createMediaElementSource(video);
          sourceNode.connect(ctx.destination);

          processor = ctx.createScriptProcessor(4096, 1, 1);
          processor.onaudioprocess = function(e) {
            var input = e.inputBuffer.getChannelData(0);
            var arr = new Array(input.length);
            for (var i = 0; i < input.length; i++) arr[i] = input[i];
            try { AndroidBridge.onAudio(JSON.stringify(arr)); } catch (err) {}
          };
          sourceNode.connect(processor);
          processor.connect(ctx.destination);
          notifyFound(true);
          log('attached to video (src=' + (video.currentSrc || video.src || '?').slice(0, 80) + ')');
          return true;
        } catch (e) {
          log('attach failed: ' + e);
          return false;
        }
      }

      function scanAll() {
        var vids = document.querySelectorAll('video');
        log('scan: found ' + vids.length + ' video tags');
        for (var i = 0; i < vids.length; i++) {
          tryAttach(vids[i]);
        }
        return vids.length > 0;
      }

      // iframe 내부도 시도 (same-origin 만)
      function scanIframes() {
        var iframes = document.querySelectorAll('iframe');
        for (var i = 0; i < iframes.length; i++) {
          try {
            var doc = iframes[i].contentDocument;
            if (!doc) continue;
            var vids = doc.querySelectorAll('video');
            for (var j = 0; j < vids.length; j++) tryAttach(vids[j]);
          } catch (e) {
            log('iframe blocked (cross-origin)');
          }
        }
      }

      if (!scanAll()) {
        notifyFound(false);
        var obs = new MutationObserver(function() {
          if (scanAll()) obs.disconnect();
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });
        log('waiting for video via MutationObserver');
      }
      scanIframes();

      window.__splayerSetSpeed = function(s) {
        var v = document.querySelector('video');
        if (v) v.playbackRate = s;
      };

      window.__splayerState = function() {
        var v = document.querySelector('video');
        return JSON.stringify({
          videoCount: document.querySelectorAll('video').length,
          hasVideo: !!v,
          paused: v ? v.paused : null,
          currentTime: v ? v.currentTime : null,
          ctxState: ctx ? ctx.state : null,
          attached: v ? !!v.__splayerAttached : false
        });
      };
    })();
    """.trimIndent()
    evaluateJavascript(js, null)
}

fun WebView.applyPlaybackSpeed(speed: Float) {
    evaluateJavascript("window.__splayerSetSpeed && window.__splayerSetSpeed($speed);", null)
}

fun WebView.queryState(callback: (String) -> Unit) {
    evaluateJavascript("window.__splayerState && window.__splayerState();") { result ->
        callback(result ?: "null")
    }
}
