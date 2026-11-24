package com.stasao.gcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Binder
import android.os.Parcel
import android.view.Surface
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Owns all closed QCarCam/HIDL libraries in an isolated process. Keeping them out
 * of the recorder process is important: their bundled platform libraries conflict
 * with the Binder stack used by Qualcomm Codec2.
 */
class QCarCamCaptureService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(RecorderHost.wrapContext(base))
    private val healthHandler = Handler(Looper.getMainLooper())
    private val hudRequested = BooleanArray(2)
    private val hudClientActive = BooleanArray(2)
    private var hudClientKnown = false
    private val hudInputs = IntArray(2) { -1 }
    private val hudErrors = arrayOfNulls<String>(2)
    private val previousHudStats = Array(2) { longArrayOf() }
    private var previousHudLogMs = 0L
    private var lastLiveVideoAtMs = 0L
    @Volatile private var hudConnected = false
    @Volatile private var hudVideoReceived = false
    @Volatile private var hudEverVideoReceived = false
    @Volatile private var hudError: String? = null
    @Volatile private var ownsCapture = false
    private var watchingLegacy = false
    private val legacyChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart !in RecorderHost.legacyPackages) return
            val available = RecorderHost.captureAvailable(context)
            if (ownsCapture && !available) {
                // Ending the isolated broker closes its vendor handles and notifies
                // all clients. The recorder process remains alive to finalize MP4s.
                ownsCapture = false
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            ownsCapture = available
        }
    }
    private val healthHeartbeat = object : Runnable {
        override fun run() {
            publishHudHealth()
            healthHandler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    private val binder = object : IQCarCamCapture.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                if (!ownsCapture) {
                    throw IllegalStateException("The camera service has been transferred to gPanel")
                }
                CaptureClientTrust.enforceCaller(this@QCarCamCaptureService, Binder.getCallingUidOrThrow(), code)
            }
            return super.onTransact(code, data, reply, flags)
        }

        override fun start(surface: Surface, inputId: Int, targetFps: Int): String {
            RecorderDiagnostics.recordHud(this@QCarCamCaptureService,
                "recorder_output_start input=$inputId fps=$targetFps callerUid=${Binder.getCallingUid()}")
            return NativeQCarCam.recorderStart(surface, inputId, targetFps)
        }

        override fun stop(inputId: Int) {
            RecorderDiagnostics.recordHud(this@QCarCamCaptureService,
                "recorder_output_stop input=$inputId callerUid=${Binder.getCallingUid()}")
            NativeQCarCam.recorderStop(inputId)
        }

        override fun status(inputId: Int): String = NativeQCarCam.recorderStatus(inputId)

        override fun stats(inputId: Int): LongArray = NativeQCarCam.recorderStats(inputId)

        override fun warmInput(inputId: Int, enabled: Boolean) {
            if (inputId in 0..1) NativeQCarCam.warmInput(inputId, enabled)
        }

        override fun setRecorderOverlay(inputId: Int, text: String) {
            if (inputId in 0..3) NativeQCarCam.recorderOverlay(inputId, text.take(80))
        }

        override fun runtimeStats(): LongArray {
            val runtime = Runtime.getRuntime()
            val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
            val load = NativeQCarCam.recorderLoad()
            return longArrayOf(
                android.os.Process.getElapsedCpuTime(),
                android.os.Debug.getNativeHeapAllocatedSize(),
                runtime.totalMemory() - runtime.freeMemory(),
                memory.totalPss.toLong(),
                memory.getMemoryStat("summary.graphics")?.toLongOrNull() ?: -1L,
                load.getOrElse(0) { 25 }, load.getOrElse(1) { 0 }
            )
        }

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
            checkBlindSlot(slot)
            if (slot !in 0..13) return "Некорректный HUD slot"
            if (slot < 2) {
                hudRequested[slot] = true
                hudInputs[slot] = inputId
                previousHudStats[slot] = longArrayOf()
                hudConnected = true
                hudErrors[slot] = null
            }
            val result = NativeQCarCam.hudStart(
                slot, surface, inputId, targetFps, cropX, cropY,
                cropZoom, fisheye, shape
            )
            if (slot < 2) {
                val accepted = result.contains("Запуск принят", ignoreCase = true)
                hudRequested[slot] = accepted
                if (!accepted) hudInputs[slot] = -1
                hudErrors[slot] = result.takeUnless { accepted }
                publishHudHealth()
            }
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
            checkBlindSlot(slot)
            NativeQCarCam.hudConfigure(slot, cropX, cropY, cropZoom, fisheye, shape)
        }

        override fun startHudV2(
            slot: Int, surface: Surface, inputId: Int, targetFps: Int,
            cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
            fisheye: Float, shape: Int, quarterTurns: Int
        ): String {
            checkBlindSlot(slot)
            if (slot !in 0..13) return "Некорректный HUD slot"
            if (slot < 2) {
                hudRequested[slot] = true
                hudInputs[slot] = inputId
                previousHudStats[slot] = longArrayOf()
                hudConnected = true
                hudErrors[slot] = null
            }
            val result = NativeQCarCam.hudStartV2(
                slot, surface, inputId, targetFps, cropX, cropY,
                cropWidth, cropHeight, fisheye, shape, quarterTurns
            )
            if (slot < 2) {
                val accepted = result.contains("Запуск принят", ignoreCase = true)
                hudRequested[slot] = accepted
                if (!accepted) hudInputs[slot] = -1
                hudErrors[slot] = result.takeUnless { accepted }
                publishHudHealth()
            }
            return result
        }

        override fun configureHudV2(
            slot: Int, cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
            fisheye: Float, shape: Int, quarterTurns: Int
        ) {
            checkBlindSlot(slot)
            NativeQCarCam.hudConfigureV2(
                slot, cropX, cropY, cropWidth, cropHeight, fisheye, shape, quarterTurns
            )
        }

        override fun stopHud(slot: Int) {
            checkBlindSlot(slot)
            if (slot !in 0..13) return
            NativeQCarCam.hudStop(slot)
            if (slot < 2) {
                hudRequested[slot] = false
                hudInputs[slot] = -1
                hudErrors[slot] = null
                publishHudHealth()
            }
        }

        override fun hudStatus(slot: Int): String {
            checkBlindSlot(slot)
            return NativeQCarCam.hudStatus(slot)
        }

        override fun hudStats(slot: Int): LongArray {
            checkBlindSlot(slot)
            return NativeQCarCam.hudStats(slot)
        }

        override fun reportBlindHealth(enabled: Boolean, connected: Boolean, video: Boolean, error: String) {
            GCamHealthReporter.send(this@QCarCamCaptureService,
                GCamHealthReporter.SOURCE_BLIND, enabled = enabled,
                hudConnected = connected, videoReceived = video, error = error.take(120))
        }

        override fun setHudClientActive(slot: Int, active: Boolean) {
            if (slot !in 0..1) return
            hudClientKnown = true
            hudClientActive[slot] = active
            hudConnected = hudClientActive.any { it }
            if (!hudConnected) {
                hudEverVideoReceived = false
                hudVideoReceived = false
                lastLiveVideoAtMs = 0L
            }
            publishHudHealth()
        }
    }

    private fun checkBlindSlot(slot: Int) {
        val uid = Binder.getCallingUidOrThrow()
        val client = CaptureClientAccess.clientFor(packageManager.getPackagesForUid(uid).orEmpty().asList())
        if (!CaptureClientAccess.canUseHudSlot(client, uid == android.os.Process.myUid(), slot)) {
            throw SecurityException("Канал камеры недоступен этому приложению")
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (RecorderHost.embedded(this)) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(this, legacyChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingLegacy = true
        }
        ownsCapture = RecorderHost.captureAvailable(this)
        previousHudLogMs = SystemClock.elapsedRealtime()
        healthHandler.post(healthHeartbeat)
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { ownsCapture }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ownsCapture) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LANGUAGE)
            GCamLanguage.useInProcess(intent.getIntExtra(EXTRA_LANGUAGE, GCamLanguage.SYSTEM))
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(HEALTH_CHANNEL_ID,
                GCamLanguage.string(this, R.string.gcam_gcam_anhud_connection), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            HEALTH_NOTIFICATION_ID,
            NotificationCompat.Builder(this, HEALTH_CHANNEL_ID)
                .setSmallIcon(R.mipmap.gcam_ic_launcher)
                .setContentTitle("gCam")
                .setContentText(GCamLanguage.string(this, R.string.gcam_cameras_ready_for_anhud))
                .setOngoing(true)
                .build()
        )
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (ownsCapture) {
            NativeQCarCam.warmInput(0, false)
            NativeQCarCam.warmInput(1, false)
        }
        hudRequested.fill(false)
        hudClientActive.fill(false)
        hudClientKnown = false
        hudInputs.fill(-1)
        hudErrors.fill(null)
        hudConnected = false
        hudVideoReceived = false
        hudEverVideoReceived = false
        lastLiveVideoAtMs = 0L
        hudError = null
        publishHudHealth()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (watchingLegacy) unregisterReceiver(legacyChanges)
        healthHandler.removeCallbacks(healthHeartbeat)
        super.onDestroy()
    }

    private fun publishHudHealth() {
        val nowMs = SystemClock.elapsedRealtime()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val sampled = Array(2) { longArrayOf() }
        var anyLiveVideo = false
        var activeRequests = false
        for (slot in 0..1) {
            if (!hudRequested[slot]) continue
            activeRequests = true
            val stats = runCatching { NativeQCarCam.hudStats(slot) }.getOrDefault(longArrayOf())
            sampled[slot] = stats
            val lastFrameNs = stats.getOrElse(4) { 0L }
            val frameFresh = lastFrameNs > 0L && nowNs >= lastFrameNs &&
                nowNs - lastFrameNs <= FRAME_STALE_NS
            if (stats.getOrElse(0) { 0L } == 1L && frameFresh) {
                anyLiveVideo = true
                hudErrors[slot] = null
            } else {
                val status = runCatching { NativeQCarCam.hudStatus(slot) }.getOrNull().orEmpty()
                hudErrors[slot] = status.takeIf {
                    it.isNotBlank() && !it.contains("ожидание", true) && !it.contains("запуск", true) &&
                        !it.contains("загрузка", true) && !it.contains("активен", true)
                } ?: hudErrors[slot]
            }
        }
        if (anyLiveVideo) {
            hudEverVideoReceived = true
            lastLiveVideoAtMs = nowMs
        }
        hudVideoReceived = if (activeRequests)
            anyLiveVideo || (hudEverVideoReceived && nowMs - lastLiveVideoAtMs <= ACTIVE_SWITCH_GRACE_MS)
        else hudEverVideoReceived
        if (hudClientKnown && !hudConnected) hudVideoReceived = false
        // An old failed slot must not turn the dot red after the turn signal is off.
        hudError = if (anyLiveVideo) null else (0..1).firstNotNullOfOrNull { slot ->
            hudErrors[slot].takeIf { hudRequested[slot] }
        }
        if (nowMs - previousHudLogMs >= HUD_LOG_INTERVAL_MS) {
            val windowMs = (nowMs - previousHudLogMs).coerceAtLeast(1L)
            RecorderDiagnostics.recordHud(this, buildString {
                append("hud_sample connected=").append(hudConnected)
                append(" active=").append(activeRequests)
                append(" videoNow=").append(anyLiveVideo)
                append(" videoEver=").append(hudEverVideoReceived)
                append(" error=").append(hudError?.replace(' ', '_') ?: "none")
                for (slot in 0..1) {
                    val stats = sampled[slot]
                    val old = previousHudStats[slot]
                    val frames = stats.getOrElse(1) { 0L }
                    val previousFrames = old.getOrElse(1) { 0L }
                    val fps = if (hudRequested[slot])
                        (frames - previousFrames).coerceAtLeast(0L) * 1000.0 / windowMs else 0.0
                    val lastNs = stats.getOrElse(4) { 0L }
                    val ageMs = if (lastNs > 0L && nowNs >= lastNs) (nowNs - lastNs) / 1_000_000 else -1L
                    append(" | projectionSlot=").append(slot)
                    append(" input=").append(hudInputs[slot])
                    append(" requested=").append(hudRequested[slot])
                    append(" fps=").append(String.format(Locale.US, "%.1f", fps))
                    append(" frames=").append(frames)
                    append(" firstFrameMs=").append(stats.getOrElse(3) { -1L })
                    append(" frameAgeMs=").append(ageMs)
                    append(" maxGapMs=").append(stats.getOrElse(15) { 0L } / 1_000)
                    append(" swapAvgUs=").append(stageAverageUs(stats, old, 28))
                    append(" drawAvgUs=").append(stageAverageUs(stats, old, 26))
                    append(" status=").append(hudErrors[slot]?.replace(' ', '_') ?: "ok")
                    previousHudStats[slot] = stats
                }
            })
            previousHudLogMs = nowMs
        }
        GCamHealthReporter.send(
            this,
            GCamHealthReporter.SOURCE_HUD,
            hudConnected = hudConnected,
            videoReceived = hudVideoReceived,
            enabled = hudConnected || hudRequested.any { it },
            error = hudError
        )
    }

    private fun stageAverageUs(current: LongArray, old: LongArray, index: Int): Long {
        val total = (current.getOrElse(index) { 0L } - old.getOrElse(index) { 0L }).coerceAtLeast(0L)
        val count = (current.getOrElse(index + 1) { 0L } - old.getOrElse(index + 1) { 0L }).coerceAtLeast(0L)
        return if (count > 0L) total / count / 1_000 else 0L
    }

    companion object {
        const val ACTION_KEEP_ALIVE = "com.stasao.gcam.KEEP_ALIVE"
        const val ACTION_LANGUAGE = "com.stasao.gcam.CAMERA_LANGUAGE"
        const val EXTRA_LANGUAGE = "language"
        const val HEALTH_INTERVAL_MS = 1_000L
        const val HUD_LOG_INTERVAL_MS = 5_000L
        const val ACTIVE_SWITCH_GRACE_MS = 2_500L
        const val FRAME_STALE_NS = 2_500_000_000L
        const val HEALTH_CHANNEL_ID = "gcam_anhud_bridge"
        const val HEALTH_NOTIFICATION_ID = 361
    }
}
