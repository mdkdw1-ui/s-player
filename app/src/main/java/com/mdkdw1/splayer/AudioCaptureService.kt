package com.mdkdw1.splayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "splayer_capture"
        const val NOTIF_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var onSamples: ((FloatArray, Int) -> Unit)? = null
        var onLevel: ((Float) -> Unit)? = null      // RMS(증폭 후, 0~1)
        var onRawLevel: ((Float) -> Unit)? = null   // RMS(원본, 0~1)
        var isRunning: Boolean = false
            private set

        // AGC 파라미터
        private const val TARGET_RMS = 0.12f        // 목표 RMS (약 -18dB)
        private const val MIN_RMS_TO_GAIN = 0.0015f // 이보다 조용하면 증폭 안 함(무음 취급)
        private const val MAX_GAIN = 15f            // 최대 15배
        private const val SMOOTH = 0.3f             // 게인 스무딩 (0=즉시, 1=고정)
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    // 스무딩된 게인 상태
    private var smoothGain = 1f
    private var lastLevelLogAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) {
            LogBus.log("CAP", "MediaProjection 결과 없음")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                LogBus.log("CAP", "MediaProjection 중지됨")
                stopCapture()
                stopSelf()
            }
        }, null)

        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val projection = mediaProjection ?: return

        val sampleRate = 48000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufSize = maxOf(minBuf, sampleRate * 2 * 2)

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        audioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            LogBus.log("CAP", "AudioRecord 생성 실패: ${e.message}")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            LogBus.log("CAP", "AudioRecord 초기화 실패")
            return
        }

        audioRecord?.startRecording()
        isRunning = true
        smoothGain = 1f
        LogBus.log("CAP", "캡처 시작 sampleRate=$sampleRate stereo, AGC on")

        captureJob = scope.launch {
            val buf = ByteArray(bufSize)
            while (isRunning) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue

                val shorts = ByteBuffer.wrap(buf, 0, n)
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = shorts.remaining() / 2
                val raw = FloatArray(frames)
                for (i in 0 until frames) {
                    val l = shorts.get(i * 2).toFloat() / 32768f
                    val r = shorts.get(i * 2 + 1).toFloat() / 32768f
                    raw[i] = (l + r) * 0.5f
                }

                // 원본 RMS
                val rawRms = rms(raw)
                onRawLevel?.invoke(rawRms)

                // AGC 적용
                val amplified = applyAgc(raw, rawRms)

                // 증폭 후 RMS
                val ampRms = rms(amplified)
                onLevel?.invoke(ampRms)

                // 3초마다 로그
                val now = System.currentTimeMillis()
                if (now - lastLevelLogAt > 3000) {
                    lastLevelLogAt = now
                    LogBus.log(
                        "CAP",
                        "rawRms=%.4f gain=%.2fx outRms=%.4f".format(rawRms, smoothGain, ampRms)
                    )
                }

                onSamples?.invoke(amplified, sampleRate)
            }
        }
    }

    private fun rms(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        var sum = 0.0
        for (s in arr) sum += s * s
        return kotlin.math.sqrt(sum / arr.size).toFloat()
    }

    /**
     * 자동 게인.
     * - 원본 RMS 가 MIN_RMS_TO_GAIN 보다 작으면 그대로 반환(무음/저잡음 증폭 방지)
     * - 그 외엔 TARGET_RMS 로 정규화, MAX_GAIN 상한
     * - 게인은 SMOOTH 계수로 지수이동평균 → 순간 볼륨 변화에 급격히 반응 안 함
     */
    private fun applyAgc(input: FloatArray, rawRms: Float): FloatArray {
        if (rawRms < MIN_RMS_TO_GAIN) {
            // 저레벨: 게인 서서히 원복
            smoothGain = smoothGain * (1 - SMOOTH) + 1f * SMOOTH
            return input
        }

        val desired = (TARGET_RMS / rawRms).coerceIn(1f, MAX_GAIN)
        smoothGain = smoothGain * (1 - SMOOTH) + desired * SMOOTH
        smoothGain = smoothGain.coerceIn(1f, MAX_GAIN)

        if (kotlin.math.abs(smoothGain - 1f) < 0.02f) {
            return input
        }

        val out = FloatArray(input.size)
        for (i in input.indices) {
            out[i] = (input[i] * smoothGain).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun stopCapture() {
        isRunning = false
        try { captureJob?.cancel() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "S-Player 캡처",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("S-Player")
            .setContentText("시스템 오디오 번역 중 (AGC)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
