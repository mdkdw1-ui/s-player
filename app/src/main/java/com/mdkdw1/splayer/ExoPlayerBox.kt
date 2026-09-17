package com.mdkdw1.splayer

import android.graphics.Color as AndroidColor
import android.net.Uri
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
    subtitleSizeFraction: Float = 0.06f,   // 0.03 ~ 0.10
    subtitleBottomPadding: Int = 0,        // dp
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    LaunchedEffect(url, subtitleFile) {
        val builder = MediaItem.Builder().setUri(url)
        if (subtitleFile != null && subtitleFile.exists()) {
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

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true

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

                // 하단 여백
                if (subtitleBottomPadding > 0) {
                    val density = resources.displayMetrics.density
                    subtitleView?.setBottomPaddingFraction(
                        subtitleBottomPadding * density / height.coerceAtLeast(1)
                    )
                }
            }
        },
        update = { view ->
            view.subtitleView?.setFractionalTextSize(subtitleSizeFraction)
        }
    )
}
