package com.mdkdw1.splayer

import android.webkit.WebView

fun WebView.injectAudioCaptureScript() {
    val js = """
    (function() {
      if (window.__splayerInstalled) {
        if (window.__splayerRescan) window.__splayerRescan();
        return;
      }
      window.__splayerInstalled = true;

      var ctx = null;
      var sourceNode = null;
      var processor = null;
      var attachedCount = 0;
      var playing = false;

      function log(msg) {
        try { AndroidBridge.onLog('JS: ' + msg); } catch (e) {}
      }

      function notifyFound(found) {
        try { AndroidBridge.onVideoFound(found); } catch (e) {}
      }

      function notifyPlayState(isPlaying) {
        if (playing !== isPlaying) {
          playing = isPlaying;
          log(isPlaying ? '재생 시작' : '재생 일시정지');
        }
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

          // 재생 상태 감지
          video.addEventListener('play', function() { notifyPlayState(true); });
          video.addEventListener('pause', function() { notifyPlayState(false); });
          video.addEventListener('playing', function() { notifyPlayState(true); });
          video.addEventListener('ended', function() { notifyPlayState(false); });

          notifyPlayState(!video.paused);
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
    })();
    """.trimIndent()
    evaluateJavascript(js, null)
}

fun WebView.applyPlaybackSpeed(speed: Float) {
    evaluateJavascript("window.__splayerSetSpeed && window.__splayerSetSpeed($speed);", null)
}
