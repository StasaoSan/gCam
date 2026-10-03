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
}
