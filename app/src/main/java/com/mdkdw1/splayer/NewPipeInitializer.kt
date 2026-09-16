package com.mdkdw1.splayer

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

object NewPipeInitializer {

    private const val TAG = "NewPipeInit"
    @Volatile private var initialized = false

    @Synchronized
    fun init() {
        if (initialized) return
        try {
            NewPipe.init(
                DownloaderImpl(),
                Localization("ko", "KR"),
                ContentCountry("KR")
            )
            initialized = true
            LogBus.log(TAG, "NewPipe 초기화 완료")
        } catch (e: Throwable) {
            Log.e(TAG, "실패", e)
            LogBus.log(TAG, "실패: ${e.message}")
            throw e
        }
    }
}
