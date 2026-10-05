package com.stasao.gcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** ADB-only control surface used by the on-car acceptance test. */
class RecorderDiagnosticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_COMMAND)?.lowercase()) {
            "start" -> {
                val old = RecorderSettings.load(context)
                val cameras = intent.getStringExtra(EXTRA_CAMERAS)
                    ?.split(',')
                    ?.mapNotNull(String::toIntOrNull)
                    ?.filter { it in 0..3 }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: old.cameraIds
                val width = intent.getIntExtra(EXTRA_WIDTH, old.width).takeIf { it in VALID_WIDTHS } ?: old.width
                val config = old.copy(
                    cameraIds = cameras,
                    width = width,
                    height = when (width) { 640 -> 400; 1280 -> 800; else -> 600 },
                    fps = intent.getIntExtra(EXTRA_FPS, old.fps).coerceIn(1, 25),
                    bitrateMbps = intent.getIntExtra(EXTRA_BITRATE, old.bitrateMbps).coerceIn(1, 20),
                    segmentMinutes = intent.getIntExtra(EXTRA_SEGMENT_MINUTES, old.segmentMinutes).coerceIn(1, 5)
                )
                RecorderSettings.save(context, config)
                Log.i(TAG, "ADB start: $config")
                DashcamService.start(context)
            }
            "stop" -> {
                Log.i(TAG, "ADB stop")
                DashcamService.stop(context)
            }
            else -> Log.e(TAG, "Unknown diagnostic command")
        }
    }

    private companion object {
        const val TAG = "GCAM_DIAGNOSTIC"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CAMERAS = "cameras"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_SEGMENT_MINUTES = "segmentMinutes"
        val VALID_WIDTHS = setOf(640, 960, 1280)
    }
}
