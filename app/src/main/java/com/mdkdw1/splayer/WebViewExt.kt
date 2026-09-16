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

      function notifyFound(found) {
        try { AndroidBridge.onVideoFound(found); } catch (e) {}
      }

      function attach(video) {
        if (!video || video.__splayerAttached) return;
        video.__splayerAttached = true;
        try {
          if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
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
          console.log('[SPlayer] audio capture attached');
        } catch (e) {
          console.log('[SPlayer] attach failed: ' + e);
        }
      }

      function findVideo() {
        var v = document.querySelector('video');
        if (v) { attach(v); return true; }
        return false;
      }

      if (!findVideo()) {
        var obs = new MutationObserver(function() {
          if (findVideo()) obs.disconnect();
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });
        notifyFound(false);
      }

      window.__splayerSetSpeed = function(s) {
        var v = document.querySelector('video');
        if (v) v.playbackRate = s;
      };
    })();
    """.trimIndent()
    evaluateJavascript(js, null)
}

fun WebView.applyPlaybackSpeed(speed: Float) {
    evaluateJavascript("window.__splayerSetSpeed && window.__splayerSetSpeed($speed);", null)
}
