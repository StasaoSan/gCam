package com.stasao.gcam

import android.view.Surface

object NativeQCarCam {
    init {
        System.loadLibrary("gcam_native")
    }

    @JvmStatic
    external fun probe(): String

    @JvmStatic external fun start(surface: Surface, inputId: Int): String
    @JvmStatic external fun hudStart(
        slot: Int,
        surface: Surface,
        inputId: Int,
        targetFps: Int,
        cropX: Float,
        cropY: Float,
        cropZoom: Float,
        fisheye: Float,
        shape: Int
    ): String
    @JvmStatic external fun hudConfigure(
        slot: Int,
        cropX: Float,
        cropY: Float,
        cropZoom: Float,
        fisheye: Float,
        shape: Int
    )
    @JvmStatic external fun hudStartV2(
        slot: Int, surface: Surface, inputId: Int, targetFps: Int,
        cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
        fisheye: Float, shape: Int, quarterTurns: Int
    ): String
    @JvmStatic external fun hudConfigureV2(
        slot: Int, cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
        fisheye: Float, shape: Int, quarterTurns: Int
    )
    @JvmStatic external fun hudStop(slot: Int)
    @JvmStatic external fun hudStatus(slot: Int): String
    @JvmStatic external fun hudStats(slot: Int): LongArray
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
