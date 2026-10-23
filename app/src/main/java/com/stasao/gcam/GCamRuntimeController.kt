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
        applyCameraOverlaySetting(context, config.cameraOverlay)
        applyRecordOverlaySetting(context, config.recordOverlay)
        // Recorder runs in a different process; pass the saved destination explicitly so its
        // SharedPreferences cache cannot revive a previously selected USB target.
        if (config.recordingAutostart) DashcamService.start(
            context, selected = RecorderSettings.load(context), waitForUsb = true
        )
    }

    fun startAfterManualLaunch(context: Context) {
        startCameraBridge(context)
        startOverlayIfAllowed(context, GCamRuntimeSettings.load(context))
        applyCameraOverlaySetting(context, GCamRuntimeSettings.load(context).cameraOverlay)
        applyRecordOverlaySetting(context, GCamRuntimeSettings.load(context).recordOverlay)
    }

    fun applyOverlaySetting(context: Context) {
        val config = GCamRuntimeSettings.load(context)
        val enabled = config.statusOverlay || config.blindStatusOverlay || config.hudStatusOverlay
        if (enabled && Settings.canDrawOverlays(context)) {
            startForeground(context, Intent(context, GCamStatusOverlayService::class.java))
        } else {
            context.stopService(Intent(context, GCamStatusOverlayService::class.java))
        }
    }

    fun applyCameraOverlaySetting(context: Context, enabled: Boolean) {
        val intent = Intent(context, GCamCameraOverlayService::class.java)
        if (enabled && Settings.canDrawOverlays(context)) startForeground(context, intent)
        else context.stopService(intent)
    }

    fun applyRecordOverlaySetting(context: Context, enabled: Boolean) {
        val intent = Intent(context, GCamRecordOverlayService::class.java)
        if (enabled && Settings.canDrawOverlays(context)) startForeground(context, intent)
        else context.stopService(intent)
    }

    fun updateRecordOverlay(context: Context) {
        if (Settings.canDrawOverlays(context)) startForeground(context,
            Intent(context, GCamRecordOverlayService::class.java)
                .setAction(GCamRecordOverlayService.ACTION_UPDATE_LAYOUT))
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
        if ((config.statusOverlay || config.blindStatusOverlay || config.hudStatusOverlay) && Settings.canDrawOverlays(context)) {
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
