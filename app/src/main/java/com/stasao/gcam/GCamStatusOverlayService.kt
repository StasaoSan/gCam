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

class GCamStatusOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var dot: GCamStatusDotView? = null
    private var layout: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastHudAt = 0L
    private var lastHudReadyAt = 0L
    private var lastRecorderAt = 0L
    private var lastBlindAt = 0L
    private var blindEnabled = false
    private var blindConnected = false
    private var blindVideo = false
    private var blindError: String? = null
    private var lastStatusLogAt = 0L
    private var lastColor = 0
    private var hudConnected = false
    private var hudEnabled = false
    private var hudVideoReceived = false
    private var hudError: String? = null
    private var hudReady = false
    private var recordingRequested = false
    private var recordingVideo = false

    private val healthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GCamHealthReporter.ACTION_HEALTH) return
            val now = SystemClock.elapsedRealtime()
            when (intent.getStringExtra(GCamHealthReporter.EXTRA_SOURCE)) {
                GCamHealthReporter.SOURCE_HUD -> {
                    lastHudAt = now
                    hudEnabled = intent.getBooleanExtra(GCamHealthReporter.EXTRA_ENABLED, false)
                    hudConnected = intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false)
                    hudVideoReceived = intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false)
                    hudError = intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR)
                    hudReady = hudConnected && hudVideoReceived && hudError.isNullOrBlank()
                    if (hudReady) lastHudReadyAt = now
                }
                GCamHealthReporter.SOURCE_RECORDER -> {
                    lastRecorderAt = now
                    recordingRequested = intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING, false)
                    recordingVideo = recordingRequested &&
                        intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING_VIDEO, false)
                }
                GCamHealthReporter.SOURCE_BLIND -> {
                    lastBlindAt = now
                    blindEnabled = intent.getBooleanExtra(GCamHealthReporter.EXTRA_ENABLED, false)
                    blindConnected = intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false)
                    blindVideo = intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false)
                    blindError = intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR)
                }
            }
            updateColor(now)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            updateColor()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(
            this,
            healthReceiver,
            IntentFilter(GCamHealthReporter.ACTION_HEALTH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (dot == null) addDot()
        if (intent?.action == ACTION_UPDATE_POSITION) {
            updatePosition(
                intent.getFloatExtra(EXTRA_X, GCamRuntimeSettings.load(this).statusOverlayX),
                intent.getFloatExtra(EXTRA_Y, GCamRuntimeSettings.load(this).statusOverlayY)
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        runCatching { unregisterReceiver(healthReceiver) }
        dot?.let { runCatching { windowManager.removeView(it) } }
        dot = null
        layout = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addDot() {
        val config = GCamRuntimeSettings.load(this)
        val maxPosition = maxPositionPx()
        val params = WindowManager.LayoutParams(
            TOUCH_SIZE_DP.dp(),
            TOUCH_SIZE_DP.dp(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (config.statusOverlayX * maxPosition.first).toInt()
            y = (config.statusOverlayY * maxPosition.second).toInt()
        }
        val view = GCamStatusDotView(this).apply { contentDescription = "Состояние gCam" }
        setupTouch(view, params)
        layout = params
        dot = view
        windowManager.addView(view, params)
        updateColor()
    }

    private fun setupTouch(view: GCamStatusDotView, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) moved = true
                    val maxPosition = maxPositionPx()
                    params.x = (initialX + event.rawX - downX).toInt().coerceIn(0, maxPosition.first)
                    params.y = (initialY + event.rawY - downY).toInt().coerceIn(0, maxPosition.second)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val maxPosition = maxPositionPx()
                    val config = GCamRuntimeSettings.load(this)
                    GCamRuntimeSettings.save(
                        this,
                        config.copy(
                            statusOverlayX = params.x.toFloat() / maxPosition.first.coerceAtLeast(1),
                            statusOverlayY = params.y.toFloat() / maxPosition.second.coerceAtLeast(1)
                        )
                    )
                    if (!moved) openApp()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateColor(now: Long = SystemClock.elapsedRealtime()) {
        val hudFresh = now - lastHudAt <= HEALTH_TIMEOUT_MS
        val recorderFresh = now - lastRecorderAt <= HEALTH_TIMEOUT_MS
        // ANHUD briefly drops its HUD slots while switching sides. Keep the last
        // healthy state during that hand-off, but never mask an explicit error.
        val hudSwitching = lastHudReadyAt > 0L &&
            now - lastHudReadyAt <= HUD_SWITCH_GRACE_MS && hudError.isNullOrBlank()
        val color = when {
            (!hudEnabled && !blindEnabled) ||
                (hudEnabled && (!hudFresh || (!hudReady && !hudSwitching))) -> GCamStatusDotView.STATUS_ERROR
            blindEnabled && (now - lastBlindAt > HEALTH_TIMEOUT_MS || !blindConnected ||
                !blindVideo || !blindError.isNullOrBlank()) -> GCamStatusDotView.STATUS_ERROR
            recordingRequested && (!recorderFresh || !recordingVideo) -> GCamStatusDotView.STATUS_ERROR
            recorderFresh && recordingVideo -> GCamStatusDotView.STATUS_RECORDING
            else -> GCamStatusDotView.STATUS_READY
        }
        if (color != lastColor || now - lastStatusLogAt >= STATUS_LOG_INTERVAL_MS) {
            RecorderDiagnostics.recordStatus(this, buildString {
                append("status_dot color=").append(when (color) {
                    GCamStatusDotView.STATUS_ERROR -> "red"
                    GCamStatusDotView.STATUS_RECORDING -> "green"
                    else -> "orange"
                })
                append(" hudFresh=").append(hudFresh)
                append(" hudConnected=").append(hudConnected)
                append(" hudEnabled=").append(hudEnabled)
                append(" hudVideo=").append(hudVideoReceived)
                append(" hudError=").append(hudError?.replace(' ', '_') ?: "none")
                append(" blindEnabled=").append(blindEnabled)
                append(" blindConnected=").append(blindConnected)
                append(" blindVideo=").append(blindVideo)
                append(" blindError=").append(blindError?.replace(' ', '_') ?: "none")
                append(" recorderFresh=").append(recorderFresh)
                append(" recordingRequested=").append(recordingRequested)
                append(" recordingVideo=").append(recordingVideo)
            })
            lastColor = color
            lastStatusLogAt = now
        }
        dot?.setStatusColor(color)
    }

    private fun updatePosition(xFraction: Float, yFraction: Float) {
        val view = dot ?: return
        val params = layout ?: return
        val maxPosition = maxPositionPx()
        params.x = (xFraction.coerceIn(0f, 1f) * maxPosition.first).toInt()
        params.y = (yFraction.coerceIn(0f, 1f) * maxPosition.second).toInt()
        windowManager.updateViewLayout(view, params)
    }

    private fun maxPositionPx(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val touchSize = TOUCH_SIZE_DP.dp()
        return (metrics.widthPixels - touchSize).coerceAtLeast(0) to
            (metrics.heightPixels - touchSize).coerceAtLeast(0)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Индикатор состояния gCam", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("gCam")
            .setContentText("Индикатор камер активен")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val ACTION_UPDATE_POSITION = "com.stasao.gcam.UPDATE_STATUS_OVERLAY_POSITION"
        const val EXTRA_X = "x_fraction"
        const val EXTRA_Y = "y_fraction"
        const val TOUCH_SIZE_DP = 36
        const val HEALTH_TIMEOUT_MS = 5_000L
        const val HUD_SWITCH_GRACE_MS = 1_500L
        const val STATUS_LOG_INTERVAL_MS = 5_000L
        const val CHANNEL_ID = "gcam_status_overlay"
        const val NOTIFICATION_ID = 362
    }
}
