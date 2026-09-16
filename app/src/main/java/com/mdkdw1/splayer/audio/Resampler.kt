package com.mdkdw1.splayer.audio

class Resampler(private val sourceRate: Int, private val targetRate: Int) {

    private var lastSample = 0f

    fun process(input: FloatArray): FloatArray {
        if (sourceRate == targetRate) return input

        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val combined = FloatArray(input.size + 1)
        System.arraycopy(input, 0, combined, 0, input.size)
        combined[combined.size - 1] = lastSample

        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)

        var srcPos = 0.0
        for (i in 0 until outLen) {
            val idx = srcPos.toInt()
            if (idx + 1 >= combined.size) break
            val frac = (srcPos - idx).toFloat()
            out[i] = combined[idx] * (1 - frac) + combined[idx + 1] * frac
            srcPos += 1.0 / ratio
        }
        lastSample = combined[combined.size - 1]
        return out
    }

    fun reset() { lastSample = 0f }
}
