package com.stasao.gcam

import android.content.Context
import android.content.Intent

object GCamHealthReporter {
    const val ACTION_HEALTH = "com.stasao.gcam.HEALTH"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_ALIVE = "alive"
    const val EXTRA_HUD_CONNECTED = "hud_connected"
    const val EXTRA_VIDEO_RECEIVED = "video_received"
    const val EXTRA_RECORDING = "recording"
    const val EXTRA_RECORDING_VIDEO = "recording_video"
    const val EXTRA_ERROR = "error"

    const val SOURCE_HUD = "hud"
    const val SOURCE_RECORDER = "recorder"

    fun send(
        context: Context,
        source: String,
        hudConnected: Boolean? = null,
        videoReceived: Boolean? = null,
        recording: Boolean? = null,
        recordingVideo: Boolean? = null,
        error: String? = null
    ) {
        context.sendBroadcast(Intent(ACTION_HEALTH).setPackage(context.packageName).apply {
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_ALIVE, true)
            hudConnected?.let { putExtra(EXTRA_HUD_CONNECTED, it) }
            videoReceived?.let { putExtra(EXTRA_VIDEO_RECEIVED, it) }
            recording?.let { putExtra(EXTRA_RECORDING, it) }
            recordingVideo?.let { putExtra(EXTRA_RECORDING_VIDEO, it) }
            error?.takeIf(String::isNotBlank)?.let { putExtra(EXTRA_ERROR, it) }
        })
    }
}
