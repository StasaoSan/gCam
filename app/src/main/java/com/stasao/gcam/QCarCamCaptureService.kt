package com.stasao.gcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.app.NotificationCompat

/**
 * Owns all closed QCarCam/HIDL libraries in an isolated process. Keeping them out
 * of the recorder process is important: their bundled platform libraries conflict
 * with the Binder stack used by Qualcomm Codec2.
 */
class QCarCamCaptureService : Service() {
    private val healthHandler = Handler(Looper.getMainLooper())
    private val hudRequested = BooleanArray(2)
    private val hudErrors = arrayOfNulls<String>(2)
    @Volatile private var hudConnected = false
    @Volatile private var hudVideoReceived = false
    @Volatile private var hudError: String? = null
    private val healthHeartbeat = object : Runnable {
        override fun run() {
            publishHudHealth()
            healthHandler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    private val binder = object : IQCarCamCapture.Stub() {
        override fun start(surface: Surface, inputId: Int, targetFps: Int): String =
            NativeQCarCam.recorderStart(surface, inputId, targetFps)

        override fun stop(inputId: Int) = NativeQCarCam.recorderStop(inputId)

        override fun status(inputId: Int): String = NativeQCarCam.recorderStatus(inputId)

        override fun stats(inputId: Int): LongArray = NativeQCarCam.recorderStats(inputId)

        override fun startHud(
            slot: Int,
            surface: Surface,
            inputId: Int,
            targetFps: Int,
            cropX: Float,
            cropY: Float,
            cropZoom: Float,
            fisheye: Float,
            shape: Int
        ): String {
            if (slot !in 0..1) return "Некорректный HUD slot"
            hudRequested[slot] = true
            hudConnected = true
            hudErrors[slot] = null
            val recorderRunning = NativeQCarCam.recorderStats(inputId).firstOrNull() == 1L
            val result = if (recorderRunning) {
                "QCarCam input $inputId занят видеорегистратором"
            } else {
                NativeQCarCam.hudStart(
                    slot,
                    surface,
                    inputId,
                    targetFps,
                    cropX,
                    cropY,
                    cropZoom,
                    fisheye,
                    shape
                )
            }
            hudErrors[slot] = result.takeIf {
                it.contains("ошиб", true) || it.contains("занят", true) || it.contains("недоступ", true)
            }
            publishHudHealth()
            return result
        }

        override fun configureHud(
            slot: Int,
            cropX: Float,
            cropY: Float,
            cropZoom: Float,
            fisheye: Float,
            shape: Int
        ) {
            NativeQCarCam.hudConfigure(slot, cropX, cropY, cropZoom, fisheye, shape)
        }

        override fun startHudV2(
            slot: Int, surface: Surface, inputId: Int, targetFps: Int,
            cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
            fisheye: Float, shape: Int, quarterTurns: Int
        ): String {
            if (slot !in 0..1) return "Некорректный HUD slot"
            hudRequested[slot] = true
            hudConnected = true
            hudErrors[slot] = null
            val recorderRunning = NativeQCarCam.recorderStats(inputId).firstOrNull() == 1L
            val result = if (recorderRunning) {
                "QCarCam input $inputId занят видеорегистратором"
            } else {
                NativeQCarCam.hudStartV2(
                    slot, surface, inputId, targetFps, cropX, cropY,
                    cropWidth, cropHeight, fisheye, shape, quarterTurns
                )
            }
            hudErrors[slot] = result.takeIf {
                it.contains("ошиб", true) || it.contains("занят", true) || it.contains("недоступ", true)
            }
            publishHudHealth()
            return result
        }

        override fun configureHudV2(
            slot: Int, cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
            fisheye: Float, shape: Int, quarterTurns: Int
        ) {
            NativeQCarCam.hudConfigureV2(
                slot, cropX, cropY, cropWidth, cropHeight, fisheye, shape, quarterTurns
            )
        }

        override fun stopHud(slot: Int) {
            if (slot !in 0..1) return
            NativeQCarCam.hudStop(slot)
            hudRequested[slot] = false
            hudErrors[slot] = null
            publishHudHealth()
        }

        override fun hudStatus(slot: Int): String = NativeQCarCam.hudStatus(slot)

        override fun hudStats(slot: Int): LongArray = NativeQCarCam.hudStats(slot)
    }

    override fun onCreate() {
        super.onCreate()
        healthHandler.post(healthHeartbeat)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(HEALTH_CHANNEL_ID, "Связь gCam с ANHUD", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            HEALTH_NOTIFICATION_ID,
            NotificationCompat.Builder(this, HEALTH_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("gCam")
                .setContentText("Камеры готовы для ANHUD")
                .setOngoing(true)
                .build()
        )
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hudRequested.fill(false)
        hudErrors.fill(null)
        hudConnected = false
        hudVideoReceived = false
        hudError = null
        publishHudHealth()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        healthHandler.removeCallbacks(healthHeartbeat)
        super.onDestroy()
    }

    private fun publishHudHealth() {
        var anyVideo = false
        for (slot in 0..1) {
            if (!hudRequested[slot] || hudErrors[slot] != null) continue
            val stats = runCatching { NativeQCarCam.hudStats(slot) }.getOrDefault(longArrayOf())
            if (stats.getOrElse(1) { 0L } > 0L) {
                anyVideo = true
                hudErrors[slot] = null
            } else {
                val status = runCatching { NativeQCarCam.hudStatus(slot) }.getOrNull().orEmpty()
                hudErrors[slot] = status.takeIf {
                    it.isNotBlank() && !it.contains("ожидание", true) && !it.contains("запуск", true) &&
                        !it.contains("загрузка", true) && !it.contains("активен", true)
                } ?: hudErrors[slot]
            }
        }
        hudVideoReceived = anyVideo
        hudError = hudErrors.firstOrNull { it != null }
        GCamHealthReporter.send(
            this,
            GCamHealthReporter.SOURCE_HUD,
            hudConnected = hudConnected,
            videoReceived = hudVideoReceived,
            error = hudError
        )
    }

    companion object {
        const val ACTION_KEEP_ALIVE = "com.stasao.gcam.KEEP_ALIVE"
        const val HEALTH_INTERVAL_MS = 1_000L
        const val HEALTH_CHANNEL_ID = "gcam_anhud_bridge"
        const val HEALTH_NOTIFICATION_ID = 361
    }
}
