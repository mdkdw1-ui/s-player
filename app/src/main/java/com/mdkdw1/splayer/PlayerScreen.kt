package com.mdkdw1.splayer

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.VolumeDown
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
    val rawLevel by vm.rawLevel.collectAsState()
    val outLevel by vm.outLevel.collectAsState()
    val logs by LogBus.lines.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var logPanelOpen by remember { mutableStateOf(false) }

    // "볼륨 낮음" 판정: 캡처 중 + 원본 RMS 가 임계 이하 + 실제 소리가 나는 중
    // 완전 무음과 구분하기 위해 약간의 시간 누적 필요. 여기서는 단순 임계값.
    val lowVolume = captureOn && rawLevel in 0.0001f..0.005f
    val silence = captureOn && rawLevel <= 0.0001f

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

        // 모델 미설치 배너
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

        // 저볼륨 경고
        AnimatedVisibility(visible = lowVolume) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VolumeDown, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "볼륨이 낮아 인식률이 떨어질 수 있습니다",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "rawRms=%.4f → AGC 증폭 중".format(rawLevel),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // 완전 무음 안내
        AnimatedVisibility(visible = silence) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "무음 구간입니다. 영상을 재생하세요.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
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

        // 레벨 미터 + 배속
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

        // 캡처 중일 때만 레벨 미터
        AnimatedVisibility(visible = captureOn) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("in ", fontSize = 9.sp, color = Color.Gray)
                    LinearProgressIndicator(
                        progress = { rawLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("out ", fontSize = 9.sp, color = Color.Gray)
                    LinearProgressIndicator(
                        progress = { outLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
