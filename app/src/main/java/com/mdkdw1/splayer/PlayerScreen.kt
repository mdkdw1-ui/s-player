package com.mdkdw1.splayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val modelFolderReady by vm.modelFolderReady.collectAsState()
    val modelFolderName by vm.modelFolderName.collectAsState()
    val subtitleSize by vm.subtitleSize.collectAsState()
    val subtitleEnabled by vm.subtitleEnabled.collectAsState()
    val history by vm.history.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val desktopUA by vm.desktopUA.collectAsState()
    val webViewReloadKey by vm.webViewReloadKey.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var whisperMenuOpen by remember { mutableStateOf(false) }
    var logPanelOpen by remember { mutableStateOf(false) }
    var sttPanelOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var cachePanelOpen by remember { mutableStateOf(false) }
    var sourceLangMenuOpen by remember { mutableStateOf(false) }
    var historyPanelOpen by remember { mutableStateOf(false) }
    var bookmarkPanelOpen by remember { mutableStateOf(false) }

    val lowVolume = captureOn && rawLevel in 0.0001f..0.005f
    val silence = captureOn && rawLevel <= 0.0001f

    val captureLauncher = rememberCapturePermissionLauncher(
        onGranted = { code, data -> vm.startCapture(code, data) },
        onDenied = { LogBus.log("CAP", "권한 거부됨") }
    )

    // SAF 폴더 선택
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.setModelFolder(uri)
            LogBus.log("UI", "폴더 지정: $uri")
        }
    }

    // 파일 선택 (로컬 STT)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            },
            actions = {
                // 파일 STT
                IconButton(onClick = {
                    filePicker.launch(arrayOf("audio/*", "video/*"))
                }) {
                    Icon(Icons.Default.Folder, contentDescription = "파일 STT")
                }

                // URL STT
                if (urlInput.isNotBlank()) {
                    IconButton(onClick = {
                        vm.runUrlStt(urlInput)
                        sttPanelOpen = true
                    }) {
                        Icon(Icons.Default.Translate, contentDescription = "URL STT")
                    }
                }

                // 더보기
                Box {
                    IconButton(onClick = { moreMenuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "더보기")
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Whisper: ${whisperModel.model.displayName}") },
                            onClick = { whisperMenuOpen = true; moreMenuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text("언어: ${modelStatus.language.displayName}") },
                            onClick = { langMenuOpen = true; moreMenuOpen = false }
                        )
                        if (mode == PlayerMode.WEBVIEW) {
                            DropdownMenuItem(
                                text = { Text("새로고침") },
                                onClick = { webViewRef?.reload(); moreMenuOpen = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("로그") },
                            onClick = { logPanelOpen = !logPanelOpen; moreMenuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text("캐시 목록") },
                            onClick = { cachePanelOpen = true; moreMenuOpen = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("📜 방문 기록") },
                            onClick = { historyPanelOpen = true; moreMenuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text("⭐ 북마크") },
                            onClick = { bookmarkPanelOpen = true; moreMenuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (desktopUA) "🌐 데스크톱 UA (ON)" else "📱 모바일 UA (OFF)") },
                            onClick = { vm.toggleDesktopUA(); moreMenuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (WebPrefs.isBookmarked(ctx, urlInput)) "⭐ 북마크 제거" else "☆ 북마크 추가") },
                            onClick = {
                                vm.toggleBookmark(urlInput, "")
                                moreMenuOpen = false
                            }
                        )
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

        // Whisper 모델 서브메뉴
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

        // 언어 서브메뉴
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

        // ===== 모델 저장 폴더 미지정 안내 =====
        if (!modelFolderReady) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("모델 저장 폴더를 선택하세요", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Download/SPlayer 폴더 권장 (PC에서 접근 가능)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text("📂 폴더 선택")
                        }
                    }
                }
            }
        }

        // ===== Whisper 모델 미설치 배너 (클라우드는 스킵) =====
        if (!whisperModel.model.isCloud && modelFolderReady && !whisperModel.installed) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Whisper ${whisperModel.model.displayName} 모델 (${whisperModel.model.sizeMb}MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        whisperModel.model.description + if (whisperModel.model.recommended) " ⭐ 권장" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "저장 위치: $modelFolderName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            TextButton(onClick = { folderPicker.launch(null) }) { Text("폴더 변경") }
                            whisperModel.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // 웹에서 영상 발견됐지만 재생 안 됨 안내
        if (mode == PlayerMode.WEBVIEW && videoFound && !sttState.running) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "영상을 재생하면 자막이 시작됩니다",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // STT 진행률 (실행 중)
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

        // 주소창
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

        // 배속 슬라이더
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

        // ===== 자막 컨트롤 (플레이어 모드일 때만) =====
        if (mode == PlayerMode.LOCAL) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    // 자막 크기 슬라이더
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("자막", fontSize = 11.sp, modifier = Modifier.width(28.dp))
                        Slider(
                            value = subtitleSize,
                            onValueChange = { vm.setSubtitleSize(it) },
                            valueRange = 0.03f..0.12f,
                            modifier = Modifier.weight(1f).height(28.dp)
                        )
                        Text("크기", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                    }

                    // 자막 ON/OFF + 배속 프리셋
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 자막 토글
                        FilterChip(
                            selected = subtitleEnabled,
                            onClick = { vm.toggleSubtitle() },
                            label = { Text(if (subtitleEnabled) "자막 ON" else "자막 OFF", fontSize = 10.sp) }
                        )
                        Spacer(Modifier.width(8.dp))

                        // 배속 프리셋
                        listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f).forEach { preset ->
                            val selected = kotlin.math.abs(speed - preset) < 0.05f
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { vm.setSpeed(preset) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "${preset}x",
                                    fontSize = 10.sp,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 본문 영역
        Box(modifier = Modifier.weight(1f)) {
            if (sttPanelOpen) {
                // STT 결과 패널
                Column(modifier = Modifier.fillMaxSize().background(Color(0xFF111111))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("STT 결과 (${sttState.segments.size})", color = Color.White, fontSize = 13.sp)
                        // === 엔진 뱃지 ===
                        sttState.engine?.let { eng ->
                            Spacer(Modifier.width(6.dp))
                            val (label, color) = when (eng) {
                                "textra" -> "NICT" to Color(0xFF7CB9E8)
                                "google" -> "Google" to Color(0xFFF4B400)
                                "cache" -> "캐시" to Color(0xFF9E9E9E)
                                "original" -> "원문" to Color(0xFF9E9E9E)
                                else -> eng to Color(0xFF9E9E9E)
                            }
                            Box(
                                modifier = Modifier
                                    .background(color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(label, color = Color.White, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))

                        // 언어 선택 드롭다운
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
                                val f = java.io.File(sttState.srtPath!!)
                                if (f.exists()) {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/x-subrip"
                                            putExtra(
                                                android.content.Intent.EXTRA_STREAM,
                                                androidx.core.content.FileProvider.getUriForFile(
                                                    ctx,
                                                    "${ctx.packageName}.fileprovider",
                                                    f
                                                )
                                            )
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(android.content.Intent.createChooser(intent, "자막 공유"))
                                    } catch (e: Exception) {
                                        LogBus.log("UI", "공유 실패: ${e.message}")
                                    }
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
                            Text("▼", fontSize = 12.sp)
                        }
                        TextButton(onClick = { vm.clearStt(); sttPanelOpen = false }) {
                            Text("✕", fontSize = 12.sp)
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        items(sttState.segments) { seg ->
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text("[${seg.startMs / 1000}s - ${seg.endMs / 1000}s]", color = Color.Gray, fontSize = 10.sp)
                                if (seg.original.isNotBlank()) {
                                    Text(seg.original, color = Color(0xFFB0D0FF), fontSize = 12.sp)
                                }
                                Text(seg.translated, color = Color.White, fontSize = 14.sp)
                            }
                            HorizontalDivider(color = Color(0xFF333333))
                        }
                    }
                }
            } else {
                when (mode) {
                    PlayerMode.LOCAL -> ExoPlayerBox(
                        url = videoUrl,
                        speed = speed,
                        subtitleFile = sttState.srtPath?.let { java.io.File(it) },
                        subtitleSizeFraction = subtitleSize,
                        subtitleEnabled = subtitleEnabled,
                        modifier = Modifier.fillMaxSize()
                    )
                    PlayerMode.WEBVIEW -> WebViewBox(
                        loadUrl = loadUrl,
                        speed = speed,
                        desktopUA = desktopUA,
                        reloadKey = webViewReloadKey,
                        onCaption = { text ->
                            vm.updateSubtitle(SubtitleCue(original = text, translated = "[번역] $text"))
                        },
                        onAudioChunk = { vm.onAudioChunk(it) },
                        onVideoFound = { vm.setVideoFound(it) },
                        onUrlChanged = {
                            vm.onWebViewUrlChanged(it)
                            vm.addHistory(it, "")
                        },
                        onLog = { vm.onJsLog(it) },
                        onWebViewReady = { webViewRef = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SubtitleOverlay(cue = subtitle)
            }
        }

        // STT 미니바
        if ((sttState.running || sttState.segments.isNotEmpty()) && !sttPanelOpen) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("[${sttState.stage}]", fontSize = 11.sp)
                            sttState.engine?.let { eng ->
                                Spacer(Modifier.width(4.dp))
                                val label = when (eng) {
                                    "textra" -> "NICT"
                                    "google" -> "Google"
                                    "cache" -> "캐시"
                                    "original" -> "원문"
                                    else -> eng
                                }
                                val c = when (eng) {
                                    "textra" -> Color(0xFF7CB9E8)
                                    "google" -> Color(0xFFF4B400)
                                    else -> Color.Gray
                                }
                                Box(
                                    modifier = Modifier
                                        .background(c.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(label, color = Color.White, fontSize = 9.sp)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(sttState.message, fontSize = 11.sp)
                        }
                        if (sttState.running) {
                            LinearProgressIndicator(
                                progress = { sttState.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    TextButton(onClick = { sttPanelOpen = true }) {
                        Text("보기", fontSize = 11.sp)
                    }
                }
            }
        }

        // 로그 패널
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
                    TextButton(onClick = {
                        try {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = logs.joinToString("\n")
                            clipboard.setPrimaryClip(ClipData.newPlainText("S-Player Logs", text))
                            android.widget.Toast.makeText(ctx, "로그 복사됨 (${logs.size}줄)", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            LogBus.log("UI", "복사 실패: ${e.message}")
                        }
                    }) { Text("📋 복사", fontSize = 12.sp) }
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

    // 방문 기록 다이얼로그
    if (historyPanelOpen) {
        AlertDialog(
            onDismissRequest = { historyPanelOpen = false },
            title = { Text("📜 방문 기록 (${history.size})") },
            text = {
                if (history.isEmpty()) {
                    Text("기록 없음")
                } else {
                    LazyColumn(modifier = Modifier.height(400.dp)) {
                        items(history) { h ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.onUrlInputChange(h.url)
                                        vm.navigateToInput()
                                        historyPanelOpen = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(h.title, fontSize = 12.sp, maxLines = 1)
                                    Text(h.url, fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); historyPanelOpen = false }) {
                    Text("전체 삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { historyPanelOpen = false }) { Text("닫기") }
            }
        )
    }

    // 북마크 다이얼로그
    if (bookmarkPanelOpen) {
        AlertDialog(
            onDismissRequest = { bookmarkPanelOpen = false },
            title = { Text("⭐ 북마크 (${bookmarks.size})") },
            text = {
                if (bookmarks.isEmpty()) {
                    Text("북마크 없음")
                } else {
                    LazyColumn(modifier = Modifier.height(400.dp)) {
                        items(bookmarks) { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            vm.onUrlInputChange(b.url)
                                            vm.navigateToInput()
                                            bookmarkPanelOpen = false
                                        }
                                ) {
                                    Text(b.title, fontSize = 12.sp, maxLines = 1)
                                    Text(b.url, fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                                }
                                TextButton(onClick = { vm.removeBookmark(b.url) }) {
                                    Text("삭제", fontSize = 10.sp)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { bookmarkPanelOpen = false }) { Text("닫기") }
            }
        )
    }

    // 캐시 목록 다이얼로그
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
                        HorizontalDivider()
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
}
