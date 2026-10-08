package com.stasao.gcam

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

data class GCamRuntimeConfig(
    val autostart: Boolean,
    val recordingAutostart: Boolean,
    val statusOverlay: Boolean,
    val statusOverlayX: Float,
    val statusOverlayY: Float
)

object GCamRuntimeSettings {
    private const val PREFS = "gcam_runtime"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_RECORDING_AUTOSTART = "recording_autostart"
    private const val KEY_STATUS_OVERLAY = "status_overlay"
    private const val KEY_STATUS_OVERLAY_X = "status_overlay_x"
    private const val KEY_STATUS_OVERLAY_Y = "status_overlay_y"
    private const val KEY_AUTOSTART_SESSION = "autostart_session"

    fun load(context: Context): GCamRuntimeConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean(KEY_AUTOSTART, false)
        val position = loadOrMigrateOverlayPosition(context)
        return GCamRuntimeConfig(
            autostart = autostart,
            recordingAutostart = autostart && prefs.getBoolean(KEY_RECORDING_AUTOSTART, false),
            statusOverlay = prefs.getBoolean(KEY_STATUS_OVERLAY, false),
            statusOverlayX = position.first,
            statusOverlayY = position.second
        )
    }

    fun save(context: Context, config: GCamRuntimeConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOSTART, config.autostart)
            .putBoolean(KEY_RECORDING_AUTOSTART, config.autostart && config.recordingAutostart)
            .putBoolean(KEY_STATUS_OVERLAY, config.statusOverlay)
            .putFloat(KEY_STATUS_OVERLAY_X, config.statusOverlayX.coerceIn(0f, 1f))
            .putFloat(KEY_STATUS_OVERLAY_Y, config.statusOverlayY.coerceIn(0f, 1f))
            .apply()
    }

    private fun loadOrMigrateOverlayPosition(context: Context): Pair<Float, Float> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_STATUS_OVERLAY_X) && prefs.contains(KEY_STATUS_OVERLAY_Y)) {
            return prefs.getFloat(KEY_STATUS_OVERLAY_X, DEFAULT_OVERLAY_X).coerceIn(0f, 1f) to
                prefs.getFloat(KEY_STATUS_OVERLAY_Y, DEFAULT_OVERLAY_Y).coerceIn(0f, 1f)
        }
        val metrics = context.resources.displayMetrics
        val legacy = context.getSharedPreferences("gcam_status_overlay", Context.MODE_PRIVATE)
        val touchPx = 36f * metrics.density
        val maxX = (metrics.widthPixels - touchPx).coerceAtLeast(1f)
        val maxY = (metrics.heightPixels - touchPx).coerceAtLeast(1f)
        val legacyRightPx = legacy.getInt("x", 10) * metrics.density
        val legacyTopPx = legacy.getInt("y", 160) * metrics.density
        val x = ((maxX - legacyRightPx) / maxX).coerceIn(0f, 1f)
        val y = (legacyTopPx / maxY).coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_STATUS_OVERLAY_X, x).putFloat(KEY_STATUS_OVERLAY_Y, y).apply()
        return x to y
    }

    fun claimAutostartForCurrentBoot(context: Context, force: Boolean = false): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val session = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
        }.getOrElse {
            (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L
        }
        if (!force && prefs.getLong(KEY_AUTOSTART_SESSION, Long.MIN_VALUE) == session) return false
        return prefs.edit().putLong(KEY_AUTOSTART_SESSION, session).commit()
    }

    fun releaseAutostartClaim(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_AUTOSTART_SESSION)
            .commit()
    }

    private const val DEFAULT_OVERLAY_X = 0.95f
    private const val DEFAULT_OVERLAY_Y = 0.33f
}
