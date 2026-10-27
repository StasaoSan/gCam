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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** A separate, movable recording control. Camera frames never pass through this overlay. */
class GCamRecordOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var button: RecordOverlayView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var storageCheckRunning = false
    private var lastStorageCheckAt = 0L
    private var storageMode = RecordingStorage.EXTERNAL
    private var storageError: String? = "Проверка USB…"
    private var lastRecorderAt = 0L
    private var recorderRequested = false
    private var recordingVideo = false
    private var recorderFailure: String? = null
    private var cameraEverFrame = false
    private var launching = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = SystemClock.elapsedRealtime()
            when (intent?.action) {
                DashcamService.ACTION_STATE -> {
                    val state = DashcamService.stateFrom(intent)
                    lastRecorderAt = now
                    recorderRequested = state.recording
                    if (!state.recording) recordingVideo = false
                    recorderFailure = state.status.takeIf {
                        it.startsWith("Запись не запущена") || it.startsWith("Не удалось") ||
                            it.startsWith("Запись остановлена")
                    }
                }
                GCamHealthReporter.ACTION_HEALTH -> when (intent.getStringExtra(GCamHealthReporter.EXTRA_SOURCE)) {
                    GCamHealthReporter.SOURCE_CAMERA -> {
                        if (intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false)) cameraEverFrame = true
                    }
                    GCamHealthReporter.SOURCE_RECORDER -> {
                        lastRecorderAt = now
                        recorderRequested = intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING, false)
                        recordingVideo = recorderRequested &&
                            intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING_VIDEO, false)
                        if (recordingVideo) recorderFailure = null
                        if (recordingVideo) cameraEverFrame = true
                        intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR)?.let { recorderFailure = it }
                    }
                    GCamHealthReporter.SOURCE_HUD -> {
                        if (intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false) &&
                            intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false) &&
                            intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR).isNullOrBlank())
                            cameraEverFrame = true
                    }
                    GCamHealthReporter.SOURCE_BLIND -> {
                        if (intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false) &&
                            intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false) &&
                            intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR).isNullOrBlank())
                            cameraEverFrame = true
                    }
                }
            }
            render()
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            refreshStorage()
            if (recorderRequested && SystemClock.elapsedRealtime() - lastRecorderAt > HEALTH_TIMEOUT_MS) {
                recorderRequested = false
                recordingVideo = false
                recorderFailure = "Нет ответа регистратора"
            }
            render()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            GCamLanguage.text(this, "Кнопка записи gCam", "gCam record button"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(GCamLanguage.text(this, "gCam — кнопка записи", "gCam — record button"))
            .setContentIntent(open)
            .setOngoing(true)
            .build())
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(this, receiver,
            IntentFilter().apply {
                addAction(DashcamService.ACTION_STATE)
                addAction(GCamHealthReporter.ACTION_HEALTH)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GCamRuntimeSettings.load(this).recordOverlay || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (button == null) addButton()
        if (intent?.action == ACTION_UPDATE_LAYOUT) updateLayout()
        if (intent?.action == ACTION_LANGUAGE) runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(GCamLanguage.text(this, "gCam — кнопка записи", "gCam — record button"))
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .setOngoing(true).build())
        }
        if (intent?.action == ACTION_LANGUAGE) render()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        scope.cancel()
        runCatching { unregisterReceiver(receiver) }
        button?.let { runCatching { windows.removeView(it) } }
        button = null
        layout = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addButton() {
        val saved = GCamRuntimeSettings.load(this)
        val size = dp(saved.recordOverlaySizeDp)
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (saved.recordOverlayX * maxPosition(size).first).toInt()
            y = (saved.recordOverlayY * maxPosition(size).second).toInt()
        }
        val view = RecordOverlayView(this)
        setupTouch(view, params)
        windows.addView(view, params)
        button = view
        layout = params
        render()
    }

    private fun updateLayout() {
        val view = button ?: return
        val params = layout ?: return
        val saved = GCamRuntimeSettings.load(this)
        val size = dp(saved.recordOverlaySizeDp)
        params.width = size
        params.height = size
        val max = maxPosition(size)
        params.x = (saved.recordOverlayX * max.first).toInt()
        params.y = (saved.recordOverlayY * max.second).toInt()
        windows.updateViewLayout(view, params)
    }

    private fun setupTouch(view: RecordOverlayView, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    downX = event.rawX; downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) moved = true
                    if (moved) {
                        val max = maxPosition(params.width)
                        params.x = (initialX + event.rawX - downX).toInt().coerceIn(0, max.first)
                        params.y = (initialY + event.rawY - downY).toInt().coerceIn(0, max.second)
                        windows.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val max = maxPosition(params.width)
                        val saved = GCamRuntimeSettings.load(this)
                        GCamRuntimeSettings.save(this, saved.copy(
                            recordOverlayX = params.x.toFloat() / max.first.coerceAtLeast(1),
                            recordOverlayY = params.y.toFloat() / max.second.coerceAtLeast(1)))
                    } else toggleRecording()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleRecording() {
        if (launching) return
        if (recorderRequested) {
            DashcamService.stop(this)
            return
        }
        val config = RecorderSettings.load(this)
        if (config.cameraIds.isEmpty()) {
            Toast.makeText(this, GCamLanguage.text(this, "Выберите камеры для записи в gCam",
                "Select cameras to record in gCam"), Toast.LENGTH_SHORT).show()
            return
        }
        if (config.storage == RecordingStorage.EXTERNAL && storageError != null) {
            Toast.makeText(this, storageError, Toast.LENGTH_LONG).show()
            return
        }
        launching = true
        recorderFailure = null
        render()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { MultiCameraPreviewController.closeAll() }
                DashcamService.start(this@GCamRecordOverlayService, config)
            }.onFailure {
                recorderFailure = "Не удалось запустить запись: ${it.message}"
                Toast.makeText(this@GCamRecordOverlayService, recorderFailure, Toast.LENGTH_LONG).show()
            }
            launching = false
            render()
        }
    }

    private fun refreshStorage() {
        val now = SystemClock.elapsedRealtime()
        if (storageCheckRunning || now - lastStorageCheckAt < 5_000L) return
        lastStorageCheckAt = now
        storageCheckRunning = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val mode = RecorderSettings.load(this@GCamRecordOverlayService).storage
                    mode to if (mode == RecordingStorage.EXTERNAL)
                        runCatching { RecordingStore.validateExternal(this@GCamRecordOverlayService) }
                            .getOrElse { it.message ?: "Ошибка USB" }
                    else null
                }
                storageMode = result.first
                storageError = result.second
                render()
            } finally {
                storageCheckRunning = false
            }
        }
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val live = recordingVideo && now - lastRecorderAt <= HEALTH_TIMEOUT_MS
        val usbUnavailable = storageMode == RecordingStorage.EXTERNAL && storageError != null
        val cameraReady = cameraEverFrame || live
        val state = when {
            live -> RecordOverlayView.RECORDING
            !cameraReady -> RecordOverlayView.ERROR
            usbUnavailable -> RecordOverlayView.USB_UNAVAILABLE
            !recorderFailure.isNullOrBlank() -> RecordOverlayView.ERROR
            else -> RecordOverlayView.IDLE
        }
        button?.apply {
            setState(state, launching || recorderRequested)
            contentDescription = GCamLanguage.display(this@GCamRecordOverlayService, buildString {
                append(if (live) "Остановить запись" else "Начать запись")
                append("; ").append(when (state) {
                    RecordOverlayView.RECORDING -> "запись идёт"
                    RecordOverlayView.USB_UNAVAILABLE -> storageError ?: "USB не готов"
                    RecordOverlayView.ERROR -> recorderFailure ?: "нет подтверждённого кадра с камер"
                    else -> if (storageMode == RecordingStorage.INTERNAL) "камеры и внутренняя память готовы" else "камеры и USB готовы"
                })
            })
        }
    }

    private fun maxPosition(size: Int): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return (metrics.widthPixels - size).coerceAtLeast(0) to
            (metrics.heightPixels - size).coerceAtLeast(0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val ACTION_UPDATE_LAYOUT = "com.stasao.gcam.UPDATE_RECORD_OVERLAY_LAYOUT"
        const val ACTION_LANGUAGE = "com.stasao.gcam.RECORD_OVERLAY_LANGUAGE"
        private const val CHANNEL_ID = "gcam_record_overlay"
        private const val NOTIFICATION_ID = 7421
        private const val HEALTH_TIMEOUT_MS = 6_000L
    }
}

/** Recording control only; ANHUD and gBlind have separate, independently enabled dots. */
private class RecordOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triangle = Path().apply {
        moveTo(54f, 36f); lineTo(54f, 63f); lineTo(76f, 49.5f); close()
    }
    private var state = ERROR
    private var starting = false

    fun setState(state: Int, starting: Boolean) {
        this.state = state; this.starting = starting
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 120f, height / 120f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(225, 15, 23, 35)
        canvas.drawCircle(60f, 60f, 59f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.WHITE
        canvas.drawCircle(60f, 60f, 57f, paint)
        paint.style = Paint.Style.FILL
        paint.color = state
        canvas.drawCircle(60f, 60f, 49f, paint)
        paint.color = Color.WHITE
        if (state == RECORDING) canvas.drawPath(triangle, paint)
        else if (starting) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 27f
            paint.isFakeBoldText = true
            canvas.drawText("…", 60f, 68f, paint)
        } else canvas.drawRoundRect(49f, 49f, 71f, 71f, 3f, 3f, paint)
        canvas.restore()
    }

    companion object {
        val RECORDING: Int = Color.rgb(76, 175, 80)
        val IDLE: Int = Color.rgb(33, 150, 243)
        val USB_UNAVAILABLE: Int = Color.rgb(255, 152, 0)
        val ERROR: Int = Color.rgb(244, 67, 54)
    }
}
