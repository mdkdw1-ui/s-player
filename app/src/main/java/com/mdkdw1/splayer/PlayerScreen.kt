package com.mdkdw1.splayer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
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

    Column(modifier = Modifier.fillMaxSize()) {
        // TopBar
        TopAppBar(
            title = { Text("S-Player") },
            actions = {
                SegmentedButton(
                    selected = mode == PlayerMode.LOCAL,
                    onClick = { vm.setMode(PlayerMode.LOCAL) }
                ) { Text("Local") }
                Spacer(Modifier.width(8.dp))
                SegmentedButton(
                    selected = mode == PlayerMode.WEBVIEW,
                    onClick = { vm.setMode(PlayerMode.WEBVIEW) }
                ) { Text("Web") }
                Spacer(Modifier.width(8.dp))
            }
        )

        // 배속 컨트롤
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Speed: %.2fx".format(speed))
            Slider(
                value = speed,
                onValueChange = { vm.setSpeed(it) },
                valueRange = 0.25f..4.0f,
                steps = 14,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text("4x")
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
                    onCaption = { text ->
                        vm.updateSubtitle(SubtitleCue(original = text, translated = "[번역] $text"))
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 자막 오버레이 (최상단)
            SubtitleOverlay(cue = subtitle)
        }
    }
}

@Composable
private fun SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) { content() }
    } else {
        OutlinedButton(onClick = onClick) { content() }
    }
}
