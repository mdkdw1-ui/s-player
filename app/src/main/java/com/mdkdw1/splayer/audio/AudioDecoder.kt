package com.mdkdw1.splayer.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    fun decodeToWav(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: WavWriter? = null

        try {
            extractor.setDataSource(input.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.e(TAG, "오디오 트랙 없음")
                return false
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "입력: $mime, ${srcRate}Hz, ${srcCh}ch")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            writer = WavWriter(output, TARGET_RATE, 1, 16)
            val resampler = Resampler(srcRate, TARGET_RATE)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalFrames = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
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
                            for (c in 0 until srcCh) {
                                sum += shorts.get(i * srcCh + c).toFloat() / 32768f
                            }
                            mono[i] = sum / srcCh
                        }
                        totalFrames += frameCount

                        val resampled = resampler.process(mono)
                        if (resampled.isNotEmpty()) {
                            writer.write(resampled, resampled.size)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "출력 포맷: ${codec.outputFormat}")
                }
            }

            Log.i(TAG, "디코딩 완료: ${totalFrames} frames")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "디코딩 실패", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
        }
    }
}
