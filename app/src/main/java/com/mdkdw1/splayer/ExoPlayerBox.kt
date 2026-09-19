package com.mdkdw1.splayer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
fun ExoPlayerBox(
    url: String,
    speed: Float,
    subtitleFile: File? = null,
    subtitleSizeFraction: Float = 0.06f,
    subtitleEnabled: Boolean = true,
    subtitleOffsetSec: Float = 0f,
    onPlaybackPosition: ((Long) -> Unit)? = null,
    useExternalSubtitle: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // 오프셋이 적용된 임시 SRT 파일
    var adjustedSrtFile by remember { mutableStateOf<File?>(null) }

    // 오프셋 변경 시 SRT 재생성
    LaunchedEffect(subtitleFile, subtitleOffsetSec) {
        if (subtitleFile == null || !subtitleFile.exists()) {
            adjustedSrtFile = null
            return@LaunchedEffect
        }
        adjustedSrtFile = if (kotlin.math.abs(subtitleOffsetSec) < 0.05f) {
            subtitleFile
        } else {
            val adjusted = File(subtitleFile.parentFile, "adjusted_${subtitleFile.name}")
            try {
                shiftSrt(subtitleFile, adjusted, subtitleOffsetSec)
                adjusted
            } catch (e: Exception) {
                subtitleFile
            }
        }
    }

    // 미디어 로드
    LaunchedEffect(url, adjustedSrtFile, subtitleEnabled, useExternalSubtitle) {
        val builder = MediaItem.Builder().setUri(url)
        val srt = adjustedSrtFile
        if (!useExternalSubtitle && subtitleEnabled && srt != null && srt.exists()) {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(srt))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ko")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(subtitle))
        }
        player.setMediaItem(builder.build())
        player.prepare()
        player.play()
    }

    LaunchedEffect(speed) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4.0f))
    }

    // 재생 위치 콜백 (100ms 폴링)
    LaunchedEffect(player, onPlaybackPosition) {
        if (onPlaybackPosition == null) return@LaunchedEffect
        while (true) {
            try {
                onPlaybackPosition(player.currentPosition)
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(100)
        }
    }

    // 전체화면 처리
    DisposableEffect(isFullscreen) {
        val activity = context as? Activity
        if (activity != null) {
            if (isFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            val act = context as? Activity
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            @Suppress("DEPRECATION")
            act?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)

                subtitleView?.setStyle(
                    CaptionStyleCompat(
                        AndroidColor.WHITE,
                        AndroidColor.argb(180, 0, 0, 0),
                        AndroidColor.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        AndroidColor.WHITE,
                        null
                    )
                )
                subtitleView?.setFractionalTextSize(subtitleSizeFraction)

                val fullscreenBtn = android.widget.ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.ic_menu_crop)
                    background = null
                    contentDescription = "전체화면"
                    setOnClickListener {
                        isFullscreen = !isFullscreen
                    }
                }
                this.addView(fullscreenBtn, android.widget.FrameLayout.LayoutParams(
                    120, 120,
                    android.view.Gravity.TOP or android.view.Gravity.END
                ).apply {
                    topMargin = 20
                    rightMargin = 20
                })
            }
        },
        update = { view ->
            view.subtitleView?.setFractionalTextSize(subtitleSizeFraction)
        }
    )
}

/**
 * SRT 타임스탬프를 offsetSec 초만큼 이동.
 */
private fun shiftSrt(input: File, output: File, offsetSec: Float) {
    val offsetMs = (offsetSec * 1000).toLong()
    val sb = StringBuilder()
    input.readLines().forEach { line ->
        val m = Regex("^(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})$").find(line)
        if (m != null) {
            val g = m.groupValues
            val startMs = g[1].toLong()*3600000 + g[2].toLong()*60000 + g[3].toLong()*1000 + g[4].toLong()
            val endMs   = g[5].toLong()*3600000 + g[6].toLong()*60000 + g[7].toLong()*1000 + g[8].toLong()
            val newStart = (startMs + offsetMs).coerceAtLeast(0)
            val newEnd = (endMs + offsetMs).coerceAtLeast(0)
            sb.append(formatSrtTime(newStart)).append(" --> ").append(formatSrtTime(newEnd)).append('\n')
        } else {
            sb.append(line).append('\n')
        }
    }
    output.writeText(sb.toString())
}

private fun formatSrtTime(ms: Long): String {
    val h = ms / 3600000
    val m = (ms % 3600000) / 60000
    val s = (ms % 60000) / 1000
    val msec = ms % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, msec)
}
