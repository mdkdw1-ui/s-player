package com.mdkdw1.splayer.audio

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriter(
    private val outFile: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private val fos = FileOutputStream(outFile)
    private var dataBytes = 0L

    init {
        outFile.parentFile?.mkdirs()
        fos.write(ByteArray(44))
    }

    fun write(samples: FloatArray, count: Int = samples.size) {
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buf.putShort(s)
        }
        fos.write(buf.array())
        dataBytes += count * 2L
    }

    fun close() {
        fos.flush()
        fos.close()

        RandomAccessFile(outFile, "rw").use { raf ->
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val totalSize = 36 + dataBytes

            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.write(intLE(totalSize.toInt()))
            raf.write("WAVE".toByteArray())

            raf.write("fmt ".toByteArray())
            raf.write(intLE(16))
            raf.write(shortLE(1))
            raf.write(shortLE(channels))
            raf.write(intLE(sampleRate))
            raf.write(intLE(byteRate))
            raf.write(shortLE(blockAlign))
            raf.write(shortLE(bitsPerSample))

            raf.write("data".toByteArray())
            raf.write(intLE(dataBytes.toInt()))
        }

        Log.i("WavWriter", "완료: ${outFile.absolutePath}, $dataBytes bytes")
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLE(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
