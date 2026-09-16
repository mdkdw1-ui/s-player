package com.mdkdw1.splayer

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel = viewModel()) {
    val mode by vm.mode.collectAsState()
    val speed by vm.speed.collectAsState()
    val subtitle by vm.subtitle.collectAsState()
    val videoUrl by vm.videoUrl.collectAsState()
    val webUrl by vm.webUrl.collectAsState()
    val videoFound by vm.videoFound.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    when {
                        mode == PlayerMode.WEBVIEW && videoFound -> "S-Player ●"
                        mode == PlayerMode.WEBVIEW -> "S-Player (웹)"
                        else -> "S-Player (로컬)"
                    }
                )
            },
            navigationIcon = {
                if (mode == PlayerMode.WEBVIEW) {
                    IconButton(onClick = { webViewRef?.goBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                }
            },
            actions = {
                if (mode == PlayerMode.WEBVIEW) {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                }
                TextButton(
                    onClick = { vm.setMode(PlayerMode.LOCAL) },
                    enabled = mode != PlayerMode.LOCAL
                ) { Text("Local") }
                TextButton(
                    onClick = { vm.setMode(PlayerMode.WEBVIEW) },
                    enabled = mode != PlayerMode.WEBVIEW
                ) { Text("Web") }
                Spacer(Modifier.width(4.dp))
            }
        )

        // 배속 슬라이더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("%.2fx".format(speed), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = speed,
                onValueChange = { vm.setSpeed(it) },
                valueRange = 0.25f..4.0f,
                steps = 14,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text("4x", style = MaterialTheme.typography.labelMedium)
        }

        // 영상 영역 + 오버레이
        Box(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                PlayerMode.LOCAL -> ExoPlayerBox(
                    url = videoUrl,
                    speed = speed,
                    modifier = Modifier.fillMaxSize()
                )
                PlayerMode.WEBVIEW -> WebViewBox(
                    url = webUrl,
                    speed = speed,
                    onCaption = { text ->
                        vm.updateSubtitle(
                            SubtitleCue(original = text, translated = "[번역] $text")
                        )
                    },
                    onAudioChunk = { vm.onAudioChunk(it) },
                    onVideoFound = { vm.setVideoFound(it) },
                    onWebViewReady = { webViewRef = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            SubtitleOverlay(cue = subtitle)
        }
    }
}
