package com.stasao.gcam

import android.content.Context

object RecorderSettings {
    private const val PREFS = "dashcam_settings"

    fun load(context: Context): RecorderConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = RecorderConfig()
        val hasSavedCameraSelection = (0..3).any { p.contains("camera_$it") }
        val cameras = (0..3).filterTo(mutableSetOf()) { id ->
            p.getBoolean("camera_$id", if (hasSavedCameraSelection) id == 2 || id == 3 else id in defaults.cameraIds)
        }
        val width = p.getInt("recording_width", defaults.width)
            .takeIf { it in setOf(640, 960, 1280) } ?: defaults.width
        val height = when (width) { 640 -> 400; 1280 -> 800; else -> 600 }
        return RecorderConfig(
            cameraIds = cameras,
            width = width,
            height = height,
            fps = p.getInt("recording_fps", defaults.fps)
                .takeIf { it in setOf(15, 20, 25) } ?: defaults.fps,
            bitrateMbps = p.getInt("bitrate_mbps", defaults.bitrateMbps)
                .takeIf { it in setOf(2, 3, 4, 6) } ?: defaults.bitrateMbps,
            segmentMinutes = p.getInt("segment_minutes", defaults.segmentMinutes).coerceIn(1, 5),
            storageLimitGb = p.getInt("storage_limit_gb", defaults.storageLimitGb).coerceIn(1, 512),
            stampTime = p.getBoolean("stamp_time", defaults.stampTime),
            stampGps = p.getBoolean("stamp_gps", defaults.stampGps),
            stampSpeed = p.getBoolean("stamp_speed", defaults.stampSpeed)
        )
    }

    fun save(context: Context, config: RecorderConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            (0..3).forEach { putBoolean("camera_$it", it in config.cameraIds) }
            putInt("recording_width", config.width)
            putInt("recording_fps", config.fps)
            putInt("bitrate_mbps", config.bitrateMbps)
            putInt("segment_minutes", config.segmentMinutes)
            putInt("storage_limit_gb", config.storageLimitGb)
            putBoolean("stamp_time", config.stampTime)
            putBoolean("stamp_gps", config.stampGps)
            putBoolean("stamp_speed", config.stampSpeed)
        }.commit()
    }

    fun loadUiScale(context: Context): Float {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("ui_scale", 1.5f)
        return saved.takeIf { it in 1.3f..1.8f } ?: 1.5f
    }

    fun saveUiScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("ui_scale", scale.coerceIn(1.3f, 1.8f)).apply()
    }
}
