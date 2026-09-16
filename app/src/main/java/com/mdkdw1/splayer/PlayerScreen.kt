package com.mdkdw1.splayer

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel = viewModel()) {
    val mode by vm.mode.collectAsState()
    val speed by vm.speed.collectAsState()
    val subtitle by vm.subtitle.collectAsState()
    val videoUrl by vm.videoUrl.collectAsState()
    val loadUrl by vm.loadUrl.collectAsState()
    val urlInput by vm.urlInput.collectAsState()
    val videoFound by vm.videoFound.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var langMenuOpen by remember { mutableStateOf(false) }

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
                // 언어 선택
                Box {
                    TextButton(onClick = { langMenuOpen = true }) {
                        Text(modelStatus.language.displayName)
                    }
                    DropdownMenu(
                        expanded = langMenuOpen,
                        onDismissRequest = { langMenuOpen = false }
                    ) {
                        SttLanguage.values().forEach { lang ->
                            DropdownMenuItem(
                                text = { Text("${lang.displayName} (${lang.approxMb}MB)") },
                                onClick = {
                                    vm.selectLanguage(lang)
                                    langMenuOpen = false
                                }
                            )
                        }
                    }
                }
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
            }
        )

        // 모델 다운로드 배너
        if (!modelStatus.installed) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "${modelStatus.language.displayName} 음성인식 모델이 필요합니다 " +
                            "(${modelStatus.language.approxMb}MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    if (modelStatus.downloading) {
                        LinearProgressIndicator(
                            progress = { modelStatus.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("다운로드 중… ${(modelStatus.progress * 100).toInt()}%")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.downloadModel() }) {
                                Text("다운로드")
                            }
                            if (modelStatus.error != null) {
                                Text(
                                    modelStatus.error ?: "",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // 주소창
        if (mode == PlayerMode.WEBVIEW) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { vm.onUrlInputChange(it) },
                singleLine = true,
                placeholder = { Text("https://example.com 또는 검색어") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { vm.navigateToInput() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // 배속 슬라이더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("%.2fx".format(speed), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = speed,
                onValueChange = { vm.setSpeed(it) },
                valueRange = 0.25f..4.0f,
                steps = 14,
                modifier = Modifier.weight(1f)
            )
            Text("4x", style = MaterialTheme.typography.labelMedium)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                PlayerMode.LOCAL -> ExoPlayerBox(
                    url = videoUrl,
                    speed = speed,
                    modifier = Modifier.fillMaxSize()
                )
                PlayerMode.WEBVIEW -> WebViewBox(
                    loadUrl = loadUrl,
                    speed = speed,
                    onCaption = { text ->
                        vm.updateSubtitle(
                            SubtitleCue(original = text, translated = "[번역] $text")
                        )
                    },
                    onAudioChunk = { vm.onAudioChunk(it) },
                    onVideoFound = { vm.setVideoFound(it) },
                    onUrlChanged = { vm.onWebViewUrlChanged(it) },
                    onWebViewReady = { webViewRef = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
            SubtitleOverlay(cue = subtitle)
        }
    }
}
