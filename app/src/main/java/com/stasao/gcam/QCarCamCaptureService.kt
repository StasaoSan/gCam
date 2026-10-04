package com.stasao.gcam

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.view.Surface

/**
 * Owns all closed QCarCam/HIDL libraries in an isolated process. Keeping them out
 * of the recorder process is important: their bundled platform libraries conflict
 * with the Binder stack used by Qualcomm Codec2.
 */
class QCarCamCaptureService : Service() {
    private val binder = object : IQCarCamCapture.Stub() {
        override fun start(surface: Surface, inputId: Int, targetFps: Int): String =
            NativeQCarCam.recorderStart(surface, inputId, targetFps)

        override fun stop(inputId: Int) = NativeQCarCam.recorderStop(inputId)

        override fun status(inputId: Int): String = NativeQCarCam.recorderStatus(inputId)

        override fun stats(inputId: Int): LongArray = NativeQCarCam.recorderStats(inputId)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
