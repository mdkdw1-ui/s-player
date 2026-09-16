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
          try { console.log('[SPlayer] ' + msg); } catch (e) {}
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
            var src = (video.currentSrc || video.src || '?');
            log('attached #' + attachedCount + ' src=' + src.slice(0, 80) + ' paused=' + video.paused);
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

        function scanIframes() {
          var iframes = document.querySelectorAll('iframe');
          log('iframes: ' + iframes.length);
          for (var i = 0; i < iframes.length; i++) {
            try {
              var doc = iframes[i].contentDocument;
              if (!doc) { log('iframe[' + i + '] no contentDocument'); continue; }
              var vids = doc.querySelectorAll('video');
              if (vids.length > 0) {
                log('iframe[' + i + '] has ' + vids.length + ' video');
                for (var j = 0; j < vids.length; j++) tryAttach(vids[j]);
              }
            } catch (e) {
              log('iframe[' + i + '] blocked');
            }
          }
        }

        function rescan() {
          log('rescan, videos=' + document.querySelectorAll('video').length + ' attached=' + attachedCount);
          scanAll();
          scanIframes();
        }
        window.__splayerRescan = rescan;

        log('installed, videos=' + document.querySelectorAll('video').length);
        rescan();

        // MutationObserver
        var obs = new MutationObserver(function() {
          scanAll();
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });

        // 주기 재스캔 (동적 로딩 대응)
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
