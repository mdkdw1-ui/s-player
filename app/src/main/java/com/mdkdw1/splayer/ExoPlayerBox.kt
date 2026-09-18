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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    LaunchedEffect(url, subtitleFile, subtitleEnabled) {
        val builder = MediaItem.Builder().setUri(url)
        if (subtitleEnabled && subtitleFile != null && subtitleFile.exists()) {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
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

    // 전체화면 상태에 따라 시스템 UI 처리
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

                // 전체화면 토글 버튼 추가
                val fullscreenBtn = android.widget.ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.ic_menu_crop)
                    background = null
                    contentDescription = "전체화면"
                    setOnClickListener {
                        isFullscreen = !isFullscreen
                    }
                }
                // PlayerView 의 컨트롤에 버튼 추가
                // (PlayerView 는 기본적으로 exo_fullscreen 버튼이 없으므로 직접 추가)
                // 간단히: PlayerView 우측 상단에 오버레이
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
