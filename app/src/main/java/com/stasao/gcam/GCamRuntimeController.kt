package com.stasao.gcam

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object GCamRuntimeController {
    fun startAfterBoot(context: Context, force: Boolean = false) {
        val config = GCamRuntimeSettings.load(context)
        if (!config.autostart) return
        if (!GCamRuntimeSettings.claimAutostartForCurrentBoot(context, force)) return
        if (!startCameraBridge(context)) {
            GCamRuntimeSettings.releaseAutostartClaim(context)
            return
        }
        startOverlayIfAllowed(context, config)
        if (config.recordingAutostart) DashcamService.start(context)
    }

    fun startAfterManualLaunch(context: Context) {
        startCameraBridge(context)
        startOverlayIfAllowed(context, GCamRuntimeSettings.load(context))
    }

    fun applyOverlaySetting(context: Context, enabled: Boolean) {
        if (enabled && Settings.canDrawOverlays(context)) {
            startForeground(context, Intent(context, GCamStatusOverlayService::class.java))
        } else {
            context.stopService(Intent(context, GCamStatusOverlayService::class.java))
        }
    }

    fun updateOverlayPosition(context: Context, x: Float, y: Float) {
        if (!Settings.canDrawOverlays(context)) return
        startForeground(
            context,
            Intent(context, GCamStatusOverlayService::class.java)
                .setAction(GCamStatusOverlayService.ACTION_UPDATE_POSITION)
                .putExtra(GCamStatusOverlayService.EXTRA_X, x.coerceIn(0f, 1f))
                .putExtra(GCamStatusOverlayService.EXTRA_Y, y.coerceIn(0f, 1f))
        )
    }

    private fun startCameraBridge(context: Context): Boolean {
        return startForeground(
            context,
            Intent(context, QCarCamCaptureService::class.java)
                .setAction(QCarCamCaptureService.ACTION_KEEP_ALIVE)
        )
    }

    private fun startOverlayIfAllowed(context: Context, config: GCamRuntimeConfig) {
        if (config.statusOverlay && Settings.canDrawOverlays(context)) {
            startForeground(context, Intent(context, GCamStatusOverlayService::class.java))
        }
    }

    private fun startForeground(context: Context, intent: Intent): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.isSuccess
    }
}
