package com.stasao.gcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Three independently movable dots: gBlind, ANHUD, and the split ANHUD/gBlind state. */
class GCamStatusOverlayService : Service() {
    private enum class Kind { BLIND, HUD, COMBINED }
    private data class FloatingDot(val view: GCamStatusDotView, val params: WindowManager.LayoutParams)

    private lateinit var windows: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val dots = mutableMapOf<Kind, FloatingDot>()
    private var lastHudAt = 0L
    private var hudEnabled = false
    private var hudConnected = false
    private var hudVideo = false
    private var hudEverFrame = false
    private var hudError: String? = null
    private var lastBlindAt = 0L
    private var blindEnabled = false
    private var blindConnected = false
    private var blindVideo = false
    private var blindEverFrame = false
    private var blindError: String? = null
    private var lastRecorderAt = 0L
    private var recordingRequested = false
    private var recordingVideo = false
    private var recordingFailed = false
    private var anhudAccess = CaptureClientTrust.Status(false, false, false, false)
    private var blindAccess = CaptureClientTrust.Status(false, false, false, false)
    private var lastAccessCheckAt = 0L
    private var lastStatusLogAt = 0L
    private var lastStatusSignature = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = SystemClock.elapsedRealtime()
            when (intent?.action) {
                DashcamService.ACTION_STATE -> {
                    val state = DashcamService.stateFrom(intent)
                    recordingRequested = state.recording
                    if (!state.recording) recordingVideo = false
                    recordingFailed = state.status.startsWith("Запись не запущена") ||
                        state.status.startsWith("Не удалось") || state.status.startsWith("Запись остановлена")
                }
                GCamHealthReporter.ACTION_HEALTH -> when (intent.getStringExtra(GCamHealthReporter.EXTRA_SOURCE)) {
                    GCamHealthReporter.SOURCE_RECORDER -> {
                        lastRecorderAt = now
                        recordingRequested = intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING, false)
                        recordingVideo = recordingRequested &&
                            intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING_VIDEO, false)
                        if (recordingVideo) recordingFailed = false
                    }
                    GCamHealthReporter.SOURCE_HUD -> {
                        lastHudAt = now
                        hudEnabled = intent.getBooleanExtra(GCamHealthReporter.EXTRA_ENABLED, false)
                        hudConnected = intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false)
                        hudVideo = intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false)
                        hudError = intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR)
                        if (hudConnected && hudVideo && hudError.isNullOrBlank()) hudEverFrame = true
                    }
                    GCamHealthReporter.SOURCE_BLIND -> {
                        lastBlindAt = now
                        blindEnabled = intent.getBooleanExtra(GCamHealthReporter.EXTRA_ENABLED, false)
                        blindConnected = intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false)
                        blindVideo = intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false)
                        blindError = intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR)
                        if (blindEnabled && blindConnected && blindVideo && blindError.isNullOrBlank())
                            blindEverFrame = true
                        if (!blindEnabled) blindEverFrame = false
                    }
                }
            }
            render(now)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            refreshAccess()
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            GCamLanguage.text(this, "Индикаторы gCam", "gCam indicators"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(GCamLanguage.text(this, "gCam · индикаторы", "gCam · indicators"))
            .setContentIntent(open)
            .setOngoing(true)
            .build())
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(GCamHealthReporter.ACTION_HEALTH)
            addAction(DashcamService.ACTION_STATE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val config = GCamRuntimeSettings.load(this)
        if (!config.statusOverlay && !config.blindStatusOverlay && !config.hudStatusOverlay) {
            stopSelf()
            return START_NOT_STICKY
        }
        syncDots(config)
        when (intent?.action) {
            ACTION_UPDATE_ALL_POSITIONS -> Kind.entries.forEach { kind ->
                val (x, y) = position(config, kind)
                positionDot(kind, x, y)
            }
            ACTION_LANGUAGE -> runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(GCamLanguage.text(this, "gCam · индикаторы", "gCam · indicators"))
                        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        .setOngoing(true).build())
            }
        }
        render()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        runCatching { unregisterReceiver(receiver) }
        dots.values.forEach { runCatching { windows.removeView(it.view) } }
        dots.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncDots(config: GCamRuntimeConfig) {
        for (kind in Kind.entries) {
            val enabled = when (kind) {
                Kind.BLIND -> config.blindStatusOverlay
                Kind.HUD -> config.hudStatusOverlay
                Kind.COMBINED -> config.statusOverlay
            }
            if (!enabled) {
                dots.remove(kind)?.let { runCatching { windows.removeView(it.view) } }
                continue
            }
            if (dots.containsKey(kind)) continue
            val size = TOUCH_SIZE_DP.dp()
            val (x, y) = position(config, kind)
            val max = maxPositionPx()
            val params = WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x * max.first).toInt()
                this.y = (y * max.second).toInt()
            }
            val view = GCamStatusDotView(this)
            setupTouch(kind, view, params)
            windows.addView(view, params)
            dots[kind] = FloatingDot(view, params)
        }
    }

    private fun setupTouch(kind: Kind, view: GCamStatusDotView, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = event.rawX; downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) moved = true
                    if (moved) {
                        val max = maxPositionPx()
                        params.x = (startX + event.rawX - downX).toInt().coerceIn(0, max.first)
                        params.y = (startY + event.rawY - downY).toInt().coerceIn(0, max.second)
                        windows.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val max = maxPositionPx()
                        val config = GCamRuntimeSettings.load(this)
                        val x = params.x.toFloat() / max.first.coerceAtLeast(1)
                        val y = params.y.toFloat() / max.second.coerceAtLeast(1)
                        GCamRuntimeSettings.save(this, when (kind) {
                            Kind.BLIND -> config.copy(blindStatusOverlayX = x, blindStatusOverlayY = y)
                            Kind.HUD -> config.copy(hudStatusOverlayX = x, hudStatusOverlayY = y)
                            Kind.COMBINED -> config.copy(statusOverlayX = x, statusOverlayY = y)
                        })
                    } else startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    })
                    true
                }
                else -> false
            }
        }
    }

    private fun render(now: Long = SystemClock.elapsedRealtime()) {
        val config = GCamRuntimeSettings.load(this)
        val expected = config.recordingAutostart || recordingRequested || recordingFailed
        val recording = recordingVideo && now - lastRecorderAt <= HEALTH_TIMEOUT_MS
        val hudReady = linkReady(anhudAccess, hudEnabled, hudConnected, hudVideo,
            hudEverFrame, hudError, lastHudAt, now)
        val blindReady = linkReady(blindAccess, blindEnabled, blindConnected, blindVideo,
            blindEverFrame, blindError, lastBlindAt, now)
        val hudColor = statusColor(hudReady, recording, expected)
        val blindColor = statusColor(blindReady, recording, expected)
        dots[Kind.HUD]?.view?.apply {
            setStatus(hudColor)
            contentDescription = "ANHUD: ${GCamLanguage.display(this@GCamStatusOverlayService, statusText(hudColor))}"
        }
        dots[Kind.BLIND]?.view?.apply {
            setStatus(blindColor)
            contentDescription = "gBlind: ${GCamLanguage.display(this@GCamStatusOverlayService, statusText(blindColor))}"
        }
        dots[Kind.COMBINED]?.view?.apply {
            setStatus(hudColor, blindColor)
            contentDescription = GCamLanguage.text(this@GCamStatusOverlayService,
                "Слева ANHUD: ${statusText(hudColor)}; справа gBlind: ${statusText(blindColor)}",
                "ANHUD left: ${GCamLanguage.display(this@GCamStatusOverlayService, statusText(hudColor))}; " +
                    "gBlind right: ${GCamLanguage.display(this@GCamStatusOverlayService, statusText(blindColor))}")
        }
        val signature = listOf(hudColor, blindColor, expected, recording).hashCode()
        if (signature != lastStatusSignature || now - lastStatusLogAt >= STATUS_LOG_INTERVAL_MS) {
            RecorderDiagnostics.recordStatus(this,
                "status_dot hud=${statusText(hudColor)} blind=${statusText(blindColor)} " +
                    "recordExpected=$expected recordingVideo=$recording " +
                    "hudEnabled=$hudEnabled hudEverFrame=$hudEverFrame blindEnabled=$blindEnabled blindEverFrame=$blindEverFrame")
            lastStatusSignature = signature
            lastStatusLogAt = now
        }
    }

    private fun linkReady(access: CaptureClientTrust.Status, enabled: Boolean, connected: Boolean,
                          video: Boolean, everFrame: Boolean, error: String?, at: Long, now: Long): Boolean {
        if (!access.installed || (!access.sameSigner && !access.approved) || !everFrame) return false
        if (now - at > HEALTH_TIMEOUT_MS || !connected) return false
        if (!enabled) return true // The turn signal is off; keep a previously verified frame while linked.
        return video && error.isNullOrBlank()
    }

    private fun statusColor(ready: Boolean, recording: Boolean, expected: Boolean): Int = when {
        !ready -> GCamStatusDotView.STATUS_ERROR
        recording -> GCamStatusDotView.STATUS_RECORDING
        expected -> GCamStatusDotView.STATUS_READY
        else -> GCamStatusDotView.STATUS_IDLE
    }

    private fun statusText(color: Int): String = when (color) {
        GCamStatusDotView.STATUS_ERROR -> "нет связи или кадра"
        GCamStatusDotView.STATUS_RECORDING -> "связь есть, запись идёт"
        GCamStatusDotView.STATUS_READY -> "связь есть, запись ожидается"
        else -> "связь есть, запись не запланирована"
    }

    private fun refreshAccess() {
        val now = SystemClock.elapsedRealtime()
        if (lastAccessCheckAt > 0L && now - lastAccessCheckAt < 5_000L) return
        lastAccessCheckAt = now
        anhudAccess = CaptureClientTrust.status(this)
        blindAccess = CaptureClientTrust.blindStatus(this)
    }

    private fun position(config: GCamRuntimeConfig, kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.BLIND -> config.blindStatusOverlayX to config.blindStatusOverlayY
        Kind.HUD -> config.hudStatusOverlayX to config.hudStatusOverlayY
        Kind.COMBINED -> config.statusOverlayX to config.statusOverlayY
    }

    private fun positionDot(kind: Kind, x: Float, y: Float) {
        val dot = dots[kind] ?: return
        val max = maxPositionPx()
        dot.params.x = (x.coerceIn(0f, 1f) * max.first).toInt()
        dot.params.y = (y.coerceIn(0f, 1f) * max.second).toInt()
        windows.updateViewLayout(dot.view, dot.params)
    }

    private fun maxPositionPx(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val size = TOUCH_SIZE_DP.dp()
        return (metrics.widthPixels - size).coerceAtLeast(0) to
            (metrics.heightPixels - size).coerceAtLeast(0)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val ACTION_UPDATE_ALL_POSITIONS = "com.stasao.gcam.UPDATE_ALL_STATUS_OVERLAY_POSITIONS"
        const val ACTION_LANGUAGE = "com.stasao.gcam.STATUS_OVERLAY_LANGUAGE"
        const val TOUCH_SIZE_DP = 36
        const val HEALTH_TIMEOUT_MS = 5_000L
        const val STATUS_LOG_INTERVAL_MS = 5_000L
        const val CHANNEL_ID = "gcam_status_overlay"
        const val NOTIFICATION_ID = 362
    }
}
