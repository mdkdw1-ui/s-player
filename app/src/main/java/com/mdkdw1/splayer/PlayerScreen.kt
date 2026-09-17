package com.mdkdw1.splayer

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
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
    val whisperModel by vm.whisperModel.collectAsState()
    val sttState by vm.sttState.collectAsState()
    val sourceLang by vm.sourceLang.collectAsState()
    val detectedLang by vm.detectedLang.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var whisperMenuOpen by remember { mutableStateOf(false) }
    var logPanelOpen by remember { mutableStateOf(false) }
    var sttPanelOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var cachePanelOpen by remember { mutableStateOf(false) }
    var sourceLangMenuOpen by remember { mutableStateOf(false) }

    val lowVolume = captureOn && rawLevel in 0.0001f..0.005f
    val silence = captureOn && rawLevel <= 0.0001f

    val captureLauncher = rememberCapturePermissionLauncher(
        onGranted = { code, data -> vm.startCapture(code, data) },
        onDenied = { LogBus.log("CAP", "권한 거부됨") }
    )

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.runLocalStt(uri)
            sttPanelOpen = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    when {
                        sttState.running -> "S-Player STT ${sttState.percent}%"
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
                // 파일 선택 (STT)
                IconButton(onClick = {
                    filePicker.launch(arrayOf("audio/*", "video/*"))
                }) {
                    Icon(Icons.Default.Folder, contentDescription = "파일 STT")
                }

                // URL STT (Translate)
                if (urlInput.isNotBlank()) {
                    IconButton(onClick = {
                        vm.runUrlStt(urlInput)
                        sttPanelOpen = true
                    }) {
                        Icon(Icons.Default.Translate, contentDescription = "URL STT")
                    }
                }

                // 더보기 메뉴 (나머지 다 여기로)
                Box {
                    IconButton(onClick = { moreMenuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "더보기")
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false }
                    ) {
                        // Whisper 모델
                        DropdownMenuItem(
                            text = { Text("Whisper: ${whisperModel.model.displayName}") },
                            onClick = { whisperMenuOpen = true; moreMenuOpen = false }
                        )
                        // 언어
                        DropdownMenuItem(
                            text = { Text("언어: ${modelStatus.language.displayName}") },
                            onClick = { langMenuOpen = true; moreMenuOpen = false }
                        )
                        // 새로고침
                        if (mode == PlayerMode.WEBVIEW) {
                            DropdownMenuItem(
                                text = { Text("새로고침") },
                                onClick = { webViewRef?.reload(); moreMenuOpen = false }
                            )
                        }
                        // 로그
                        DropdownMenuItem(
                            text = { Text("로그") },
                            onClick = { logPanelOpen = !logPanelOpen; moreMenuOpen = false }
                        )
                        // 캐시 목록
                        DropdownMenuItem(
                            text = { Text("캐시 목록") },
                            onClick = { cachePanelOpen = true; moreMenuOpen = false }
                        )
                        // 캡처 (실시간, 지금은 스텁)
                        DropdownMenuItem(
                            text = { Text(if (captureOn) "캡처 중지" else "캡처 시작") },
                            onClick = {
                                if (captureOn) vm.stopCapture()
                                else captureLauncher.launch(buildCaptureIntent(ctx))
                                moreMenuOpen = false
                            }
                        )
                    }
                }
            }
        )

        // Whisper 모델 선택 서브메뉴
        DropdownMenu(
            expanded = whisperMenuOpen,
            onDismissRequest = { whisperMenuOpen = false }
        ) {
            WhisperModel.values().forEach { m ->
                val installed = remember(m) {
                    WhisperModelDownloader.isInstalled(ctx, m)
                }
                DropdownMenuItem(
                    text = { Text("${m.displayName} (${m.sizeMb}MB)${if (installed) " ✓" else ""}") },
                    onClick = { vm.selectWhisperModel(m); whisperMenuOpen = false }
                )
            }
        }

        // 언어 선택 서브메뉴
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

        // 이하 UI는 이전과 동일 (모델 배너, STT 진행률, 주소창, 배속 슬라이더, 영상 영역, 로그 패널)
        // ... (생략하지 않고 전체 복사)
        if (!whisperModel.installed) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Whisper ${whisperModel.model.displayName} 모델 필요 (${whisperModel.model.sizeMb}MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        whisperModel.model.description + if (whisperModel.model.recommended) " ⭐ 권장" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (whisperModel.downloading) {
                        LinearProgressIndicator(
                            progress = { whisperModel.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("다운로드 ${(whisperModel.progress * 100).toInt()}%")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.downloadWhisperModel() }) { Text("다운로드") }
                            whisperModel.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = sttState.running) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                LinearProgressIndicator(
                    progress = { sttState.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("[${sttState.stage}] ${sttState.message}", fontSize = 11.sp)
            }
        }

        if (mode == PlayerMode.WEBVIEW && !sttPanelOpen) {
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
            if (sttPanelOpen) {
                Column(modifier = Modifier.fillMaxSize().background(Color(0xFF111111))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("STT 결과 (${sttState.segments.size})", color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        // 소스 언어 선택
                        Box {
                            TextButton(onClick = { sourceLangMenuOpen = true }) {
                                val label = if (sourceLang == SttSourceLang.AUTO && detectedLang != null) {
                                    "자동($detectedLang)"
                                } else {
                                    sourceLang.displayName
                                }
                                Text(label, fontSize = 11.sp, color = Color(0xFFB0D0FF))
                            }
                            DropdownMenu(
                                expanded = sourceLangMenuOpen,
                                onDismissRequest = { sourceLangMenuOpen = false }
                            ) {
                                SttSourceLang.values().forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text("${lang.displayName} (${lang.code})") },
                                        onClick = {
                                            vm.setSourceLang(lang)
                                            sourceLangMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!sttState.running && sttState.srtPath != null) {
                            TextButton(onClick = {
                                // 공유
                                val f = java.io.File(sttState.srtPath!!)
                                if (f.exists()) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/x-subrip"
                                        putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                                            ctx,
                                            "${ctx.packageName}.fileprovider",
                                            f
                                        ))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(intent, "자막 공유"))
                                }
                            }) {
                                Text("공유", fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                vm.playLastStream()
                                sttPanelOpen = false
                            }) {
                                Text("▶ 재생", fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = { sttPanelOpen = false }) {
                            Text("▼ 미니로", fontSize = 12.sp)
                        }
                        TextButton(onClick = { vm.clearStt(); sttPanelOpen = false }) {
                            Text("✕", fontSize = 12.sp)
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        items(sttState.segments) { seg ->
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text("[${seg.startMs / 1000}s - ${seg.endMs / 1000}s]", color = Color.Gray, fontSize = 10.sp)
                                Text(seg.original, color = Color(0xFFB0D0FF), fontSize = 12.sp)
                                Text(seg.translated, color = Color.White, fontSize = 14.sp)
                            }
                            Divider(color = Color(0xFF333333))
                        }
                    }
                }
            } else {
                when (mode) {
                    PlayerMode.LOCAL -> ExoPlayerBox(
                        url = videoUrl,
                        speed = speed,
                        subtitleFile = sttState.srtPath?.let { java.io.File(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                    PlayerMode.WEBVIEW -> WebViewBox(
                        loadUrl = loadUrl,
                        speed = speed,
                        onCaption = { text -> vm.updateSubtitle(SubtitleCue(original = text, translated = "[번역] $text")) },
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
        }

        // 캐시 목록 패널
        if (cachePanelOpen) {
            val cacheFiles = remember { SubtitleCache.listAll(ctx) }
            AlertDialog(
                onDismissRequest = { cachePanelOpen = false },
                title = { Text("자막 캐시 (${cacheFiles.size})") },
                text = {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(cacheFiles) { f ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 11.sp)
                                    Text("${f.length() / 1024}KB", fontSize = 9.sp, color = Color.Gray)
                                }
                                TextButton(onClick = {
                                    SubtitleCache.delete(ctx, f)
                                    cachePanelOpen = false
                                }) {
                                    Text("삭제", fontSize = 11.sp)
                                }
                            }
                            Divider()
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { SubtitleCache.clearAll(ctx); cachePanelOpen = false }) {
                        Text("전체 삭제")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cachePanelOpen = false }) {
                        Text("닫기")
                    }
                }
            )
        }

        // STT 진행 중이거나 결과가 있을 때, 패널 닫혀 있으면 하단 미니바
        if ((sttState.running || sttState.segments.isNotEmpty()) && !sttPanelOpen) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("[${sttState.stage}] ${sttState.message}", fontSize = 11.sp)
                        LinearProgressIndicator(
                            progress = { sttState.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(onClick = { sttPanelOpen = true }) {
                        Text("보기", fontSize = 11.sp)
                    }
                }
            }
        }

        if (logPanelOpen) {
            Column(
                modifier = Modifier.fillMaxWidth().height(200.dp).background(Color(0xEE111111))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("로그 (${logs.size})", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { LogBus.clear() }) { Text("지우기", fontSize = 12.sp) }
                    TextButton(onClick = { logPanelOpen = false }) { Text("닫기", fontSize = 12.sp) }
                }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(logs) { line ->
                        Text(line, color = Color(0xFFB0FFB0), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}
