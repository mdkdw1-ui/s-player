package com.mdkdw1.splayer

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel = viewModel()) {
    val ctx = LocalContext.current

    val mode by vm.mode.collectAsState()
    val speed by vm.speed.collectAsState()
    val subtitle by vm.subtitle.collectAsState()
    val videoUrl by vm.videoUrl.collectAsState()
    val loadUrl by vm.loadUrl.collectAsState()
    val urlInput by vm.urlInput.collectAsState()
    val videoFound by vm.videoFound.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()
    val captureOn by vm.captureOn.collectAsState()
    val logs by LogBus.lines.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var logPanelOpen by remember { mutableStateOf(false) }

    val captureLauncher = rememberCapturePermissionLauncher(
        onGranted = { code, data -> vm.startCapture(code, data) },
        onDenied = { LogBus.log("CAP", "권한 거부됨") }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    when {
                        captureOn -> "S-Player ●REC"
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
                // 시스템 오디오 캡처 토글
                IconButton(onClick = {
                    if (captureOn) vm.stopCapture()
                    else captureLauncher.launch(buildCaptureIntent(ctx))
                }) {
                    Icon(
                        if (captureOn) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "시스템 오디오 캡처",
                        tint = if (captureOn) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface
                    )
                }

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
                                onClick = { vm.selectLanguage(lang); langMenuOpen = false }
                            )
                        }
                    }
                }

                if (mode == PlayerMode.WEBVIEW) {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                }

                IconButton(onClick = { logPanelOpen = !logPanelOpen }) {
                    Icon(Icons.Default.BugReport, contentDescription = "로그")
                }
            }
        )

        if (!modelStatus.installed) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "${modelStatus.language.displayName} 모델 필요 (${modelStatus.language.approxMb}MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    if (modelStatus.downloading) {
                        LinearProgressIndicator(
                            progress = { modelStatus.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("다운로드 ${(modelStatus.progress * 100).toInt()}%")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.downloadModel() }) { Text("다운로드") }
                            modelStatus.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        if (mode == PlayerMode.WEBVIEW) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { vm.onUrlInputChange(it) },
                singleLine = true,
                placeholder = { Text("URL 또는 검색어") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { vm.navigateToInput() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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

        Box(modifier = Modifier.weight(1f)) {
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
                        vm.updateSubtitle(SubtitleCue(original = text, translated = "[번역] $text"))
                    },
                    onAudioChunk = { vm.onAudioChunk(it) },
                    onVideoFound = { vm.setVideoFound(it) },
                    onUrlChanged = { vm.onWebViewUrlChanged(it) },
                    onLog = { vm.onJsLog(it) },
                    onWebViewReady = { webViewRef = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
            SubtitleOverlay(cue = subtitle)
        }

        if (logPanelOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xEE111111))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("로그 (${logs.size})", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { LogBus.clear() }) {
                        Text("지우기", fontSize = 12.sp)
                    }
                    TextButton(onClick = { logPanelOpen = false }) {
                        Text("닫기", fontSize = 12.sp)
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            color = Color(0xFFB0FFB0),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
