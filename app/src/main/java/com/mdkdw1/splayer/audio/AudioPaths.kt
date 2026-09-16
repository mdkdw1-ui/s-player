package com.mdkdw1.splayer.audio

import android.content.Context
import java.io.File

object AudioPaths {

    fun audioDir(context: Context): File =
        File(context.cacheDir, "audio").apply { mkdirs() }

    fun subtitleDir(context: Context): File =
        File(context.filesDir, "subtitles").apply { mkdirs() }

    fun modelDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun tempWav(context: Context, tag: String): File =
        File(audioDir(context), "${tag}.wav")

    fun tempAudioInput(context: Context, tag: String, ext: String): File =
        File(audioDir(context), "$tag.$ext")
}
