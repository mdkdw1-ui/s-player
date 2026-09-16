package com.mdkdw1.splayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

data class StreamResult(
    val title: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    val videoUrl: String?,
    val audioUrl: String?,
    val videoMimeType: String?,
    val audioMimeType: String?,
    val service: String
)

object StreamExtractor {

    private const val TAG = "StreamExtractor"

    suspend fun extract(url: String): Result<StreamResult> = withContext(Dispatchers.IO) {
        try {
            LogBus.log(TAG, "extract: $url")
            NewPipeInitializer.init()

            val service = NewPipe.getServiceByUrl(url)
            LogBus.log(TAG, "service: ${service.serviceInfo.name}")

            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()

            val title = extractor.name ?: "제목 없음"
            val durationSec = extractor.length
            // v0.26.5: thumbnails (복수형), 첫 번째 것 사용
            val thumbnail: String? = try {
                extractor.thumbnails.firstOrNull()?.url
            } catch (e: Exception) { null }

            LogBus.log(TAG, "title: $title, duration: ${durationSec}s")

            // 오디오 스트림 (최고 비트레이트)
            val audioStreams = extractor.audioStreams
            val bestAudio: AudioStream? = audioStreams
                .filter { it.url != null && it.isUrl }
                .maxByOrNull { it.averageBitrate }

            // 비디오 스트림 (m4a 우선)
            val videoStreams = extractor.videoStreams
            val bestVideo: VideoStream? = videoStreams
                .filter { it.url != null && it.isUrl }
                .filter { it.getFormat()?.getName()?.contains("MPEG-4") == true }
                .maxByOrNull { it.getBitrate() }
                ?: videoStreams
                    .filter { it.url != null && it.isUrl }
                    .maxByOrNull { it.getBitrate() }

            if (bestAudio == null && bestVideo == null) {
                LogBus.log(TAG, "스트림 URL 없음")
                return@withContext Result.failure(Exception("스트림 URL을 찾을 수 없습니다"))
            }

            val result = StreamResult(
                title = title,
                durationSec = durationSec,
                thumbnailUrl = thumbnail,
                videoUrl = bestVideo?.url,
                audioUrl = bestAudio?.url,
                videoMimeType = bestVideo?.getFormat()?.getMimeType(),
                audioMimeType = bestAudio?.getFormat()?.getMimeType(),
                service = service.serviceInfo.name
            )

            LogBus.log(TAG, "완료: audio=${bestAudio?.averageBitrate}kbps")
            Result.success(result)

        } catch (e: Throwable) {
            LogBus.log(TAG, "실패: ${e.message}")
            Log.e(TAG, "extract 실패", e)
            Result.failure(e)
        }
    }
}
