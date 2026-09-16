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

        // 캡처 콜백 (앱 프로세스 내에서 공유)
        var onSamples: ((FloatArray, Int) -> Unit)? = null
        var isRunning: Boolean = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

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
        val bufSize = maxOf(minBuf, sampleRate * 2 * 2) // 1초 분량

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
        LogBus.log("CAP", "캡처 시작 sampleRate=$sampleRate stereo")

        captureJob = scope.launch {
            val buf = ByteArray(bufSize)
            while (isRunning) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue

                // 16bit stereo → FloatArray (모노 다운믹스)
                val shorts = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = shorts.remaining() / 2  // stereo
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    val l = shorts.get(i * 2).toFloat() / 32768f
                    val r = shorts.get(i * 2 + 1).toFloat() / 32768f
                    out[i] = (l + r) * 0.5f
                }

                onSamples?.invoke(out, sampleRate)
            }
        }
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
            .setContentText("시스템 오디오 번역 중")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
