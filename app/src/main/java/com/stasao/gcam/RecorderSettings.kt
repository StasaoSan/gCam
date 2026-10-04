package com.stasao.gcam

import android.content.Context

object RecorderSettings {
    private const val PREFS = "dashcam_settings"

    fun load(context: Context): RecorderConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cameras = (0..3).filterTo(mutableSetOf()) { id ->
            p.getBoolean("camera_$id", id == 2 || id == 3)
        }
        return RecorderConfig(
            cameraIds = cameras,
            bitrateMbps = p.getInt("bitrate_mbps", 4).coerceIn(2, 8),
            segmentMinutes = p.getInt("segment_minutes", 2).coerceIn(1, 5),
            storageLimitGb = p.getInt("storage_limit_gb", 40).coerceIn(1, 512)
        )
    }

    fun save(context: Context, config: RecorderConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            (0..3).forEach { putBoolean("camera_$it", it in config.cameraIds) }
            putInt("bitrate_mbps", config.bitrateMbps)
            putInt("segment_minutes", config.segmentMinutes)
            putInt("storage_limit_gb", config.storageLimitGb)
        }.apply()
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
