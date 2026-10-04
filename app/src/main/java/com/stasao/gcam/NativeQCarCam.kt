package com.stasao.gcam

import android.view.Surface

object NativeQCarCam {
    init {
        System.loadLibrary("gcam_native")
    }

    @JvmStatic
    external fun probe(): String

    @JvmStatic external fun start(surface: Surface, inputId: Int): String
    @JvmStatic external fun stop()
    @JvmStatic external fun status(): String

    /** running, frames, dropped/timeouts, firstFrameMs, lastFrameMonotonicNs */
    @JvmStatic external fun stats(): LongArray

    @JvmStatic external fun recorderStart(surface: Surface, inputId: Int): String
    @JvmStatic external fun recorderStop(inputId: Int)
    @JvmStatic external fun recorderStatus(inputId: Int): String
    /** running, frames, dropped/timeouts, firstFrameMs, lastFrameMonotonicNs */
    @JvmStatic external fun recorderStats(inputId: Int): LongArray
}
