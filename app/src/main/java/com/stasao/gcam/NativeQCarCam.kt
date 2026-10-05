package com.stasao.gcam

import android.view.Surface

object NativeQCarCam {
    init {
        System.loadLibrary("gcam_native")
    }

    @JvmStatic
    external fun probe(): String

    @JvmStatic external fun start(surface: Surface, inputId: Int): String
    @JvmStatic external fun hudStart(surface: Surface, inputId: Int, targetFps: Int): String
    @JvmStatic external fun stop()
    @JvmStatic external fun status(): String

    /**
     * running, frames, dropped/timeouts, firstFrameMs, lastFrameMonotonicNs,
     * getFrame avg/max us, memcpy avg/max us, GPU submit avg/max us,
     * eglSwapBuffers avg/max us, ION hold avg/max us, max frame gap us, zeroCopy.
     */
    @JvmStatic external fun stats(): LongArray

    @JvmStatic external fun recorderStart(surface: Surface, inputId: Int, targetFps: Int): String
    @JvmStatic external fun recorderStop(inputId: Int)
    @JvmStatic external fun recorderStatus(inputId: Int): String
    /** Same 17 counters as [stats]. */
    @JvmStatic external fun recorderStats(inputId: Int): LongArray
}
