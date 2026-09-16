package com.mdkdw1.splayer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SubtitleOverlay(
    cue: SubtitleCue,
    modifier: Modifier = Modifier
) {
    if (cue.original.isBlank() && cue.translated.isBlank()) return

    val mainText = cue.translated.ifBlank { cue.original }
    val showOriginal = cue.translated.isNotBlank() && cue.original.isNotBlank()
    val showTranslatedOnly = cue.translated.isNotBlank() && cue.original.isBlank()

    // partial 이면 배경 흐리게, final 이면 진하게
    val bgColor by animateColorAsState(
        targetValue = if (cue.isFinal) Color(0xDD000000) else Color(0x99000000),
        label = "bg"
    )
    val mainColor by animateColorAsState(
        targetValue = if (cue.isFinal) Color.White else Color(0xFFD0D0D0),
        label = "main"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (showOriginal) {
                Text(
                    text = cue.original,
                    color = Color(0xFFB0D0FF),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = Shadow(Color.Black, Offset(1f, 1f), 2f)
                    )
                )
                Spacer(Modifier.height(2.dp))
            }

            Text(
                text = mainText,
                color = mainColor,
                fontSize = if (showTranslatedOnly) 17.sp else 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 26.sp,
                style = TextStyle(
                    shadow = Shadow(Color.Black, Offset(1f, 1f), 3f)
                )
            )
        }
    }
}
