package com.mdkdw1.splayer.audio

import android.media.MediaDataSource

class StreamingDataSource(private val source: StreamingSource) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        return source.readAt(position, buffer, offset, size)
    }

    override fun getSize(): Long {
        return if (source.totalSize > 0) source.totalSize else -1L
    }

    override fun close() {
        source.close()
    }
}
