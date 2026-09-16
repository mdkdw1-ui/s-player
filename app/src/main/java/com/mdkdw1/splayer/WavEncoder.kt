package com.mdkdw1.splayer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {

    /**
     * FloatArray ([-1, 1]) 를 16-bit mono PCM WAV 로 인코딩
     * @param samples 원본 오디오 샘플
     * @param sampleRate 원본 샘플레이트 (Web Audio 는 보통 44100 또는 48000)
     * @param targetRate STT 엔진 권장 샘플레이트 (Whisper 는 16000)
     */
    fun encode(
        samples: FloatArray,
        sampleRate: Int = 48000,
        targetRate: Int = 16000
    ): ByteArray {
        val resampled = resample(samples, sampleRate, targetRate)
        val pcm16 = floatToPcm16(resampled)

        val out = ByteArrayOutputStream()
        val dataSize = pcm16.size
        val byteRate = targetRate * 2
        val totalSize = 36 + dataSize

        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intLE(totalSize))
        out.write("WAVE".toByteArray())

        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intLE(16))
        out.write(shortLE(1))            // PCM
        out.write(shortLE(1))            // mono
        out.write(intLE(targetRate))
        out.write(intLE(byteRate))
        out.write(shortLE(2))            // block align
        out.write(shortLE(16))           // bits per sample

        // data chunk
        out.write("data".toByteArray())
        out.write(intLE(dataSize))
        out.write(pcm16)

        return out.toByteArray()
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        val ratio = to.toDouble() / from.toDouble()
        val outLen = (input.size * ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcIdx = i / ratio
            val i0 = srcIdx.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcIdx - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    private fun floatToPcm16(input: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(input.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in input) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buf.putShort(s)
        }
        return buf.array()
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLE(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
