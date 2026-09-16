package com.mdkdw1.splayer.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    /** 파일 기반 (기존) */
    fun decodeToWav(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource 실패", e)
            return false
        }
        return decodeInternal(extractor, output)
    }

    /** 스트리밍 기반 (신규) */
    fun decodeToWavFromSource(dataSource: MediaDataSource, output: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(dataSource)
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource(source) 실패", e)
            return false
        }
        return decodeInternal(extractor, output)
    }

    private fun decodeInternal(extractor: MediaExtractor, output: File): Boolean {
        var codec: MediaCodec? = null
        var writer: WavWriter? = null

        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) {
                Log.e(TAG, "오디오 트랙 없음")
                return false
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "디코딩: $mime, ${srcRate}Hz, ${srcCh}ch")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            writer = WavWriter(output, TARGET_RATE, 1, 16)
            val resampler = Resampler(srcRate, TARGET_RATE)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frames = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameCount = shorts.remaining() / srcCh
                        val mono = FloatArray(frameCount)
                        for (i in 0 until frameCount) {
                            var sum = 0f
                            for (c in 0 until srcCh) sum += shorts.get(i * srcCh + c).toFloat() / 32768f
                            mono[i] = sum / srcCh
                        }
                        frames += frameCount
                        val resampled = resampler.process(mono)
                        if (resampled.isNotEmpty()) writer.write(resampled, resampled.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            Log.i(TAG, "디코딩 완료: ${frames} frames")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "디코딩 예외", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
        }
    }
}
