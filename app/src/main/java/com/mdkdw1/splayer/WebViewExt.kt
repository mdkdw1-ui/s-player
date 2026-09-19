package com.mdkdw1.splayer

import android.webkit.WebView

fun WebView.injectAudioCaptureScript() {
    val js = """
    (function() {
      try {
        if (window.__splayerInstalled) {
          try { AndroidBridge.onLog('JS: already installed, rescan'); } catch (e) {}
          if (window.__splayerRescan) window.__splayerRescan();
          return;
        }
        window.__splayerInstalled = true;

        var ctx = null;
        var sourceNode = null;
        var processor = null;
        var attachedCount = 0;

        function log(msg) {
          try { AndroidBridge.onLog('JS: ' + msg); } catch (e) {}
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
            attachedCount++;
            notifyFound(true);
            log('attached #' + attachedCount + ' (paused=' + video.paused + ')');
            return true;
          } catch (e) {
            log('attach failed: ' + e);
            return false;
          }
        }

        function scanAll() {
          var vids = document.querySelectorAll('video');
          var found = false;
          for (var i = 0; i < vids.length; i++) {
            if (tryAttach(vids[i])) found = true;
          }
          return found;
        }

        function rescan() {
          scanAll();
        }
        window.__splayerRescan = rescan;

        rescan();

        var obs = new MutationObserver(function() { scanAll(); });
        obs.observe(document.documentElement, { childList: true, subtree: true });

        var tries = 0;
        var timer = setInterval(function() {
          tries++;
          rescan();
          if (tries > 10) clearInterval(timer);
        }, 1000);

        window.__splayerSetSpeed = function(s) {
          var v = document.querySelector('video');
          if (v) v.playbackRate = s;
        };

        // ===== SPA URL 변경 감지 =====
        try {
          var lastUrl = location.href;
          function reportUrl() {
            if (location.href !== lastUrl) {
              lastUrl = location.href;
              try { AndroidBridge.onUrlChanged(location.href); } catch (e) {}
            }
          }
          var origPush = history.pushState;
          history.pushState = function() {
            origPush.apply(this, arguments);
            reportUrl();
          };
          var origReplace = history.replaceState;
          history.replaceState = function() {
            origReplace.apply(this, arguments);
            reportUrl();
          };
          window.addEventListener('popstate', reportUrl);
          window.addEventListener('hashchange', reportUrl);
          setInterval(reportUrl, 3000);
          log('URL 감지 활성화');
        } catch (e) {
          log('URL 감지 실패: ' + e);
        }
      } catch (globalErr) {
        try { AndroidBridge.onLog('JS FATAL: ' + globalErr); } catch (e) {}
      }
    })();
    """.trimIndent()
    evaluateJavascript(js, null)
}

fun WebView.applyPlaybackSpeed(speed: Float) {
    evaluateJavascript("window.__splayerSetSpeed && window.__splayerSetSpeed($speed);", null)
}
