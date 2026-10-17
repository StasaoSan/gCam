package com.stasao.gcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.min

/** Four small surfaces share the existing QCarCam inputs; no second camera capture is opened. */
class GCamCameraOverlayService : Service() {
    private lateinit var windows: WindowManager
    private var panel: View? = null
    private var capture: FloatingCameraCapture? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Плавающие камеры", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("gCam: камеры поверх окон")
            .setOngoing(true)
            .build())
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GCamRuntimeSettings.load(this).cameraOverlay || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel == null) showPanel()
        return START_STICKY
    }

    override fun onDestroy() {
        panel?.let { runCatching { windows.removeView(it) } }
        panel = null
        capture?.close()
        capture = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showPanel() {
        val cameraCapture = FloatingCameraCapture(this)
        capture = cameraCapture
        val dm = resources.displayMetrics
        val width = min((dm.widthPixels * 0.46f).toInt(), dp(440)).coerceAtLeast(dp(220))
        val params = WindowManager.LayoutParams(
            width, dp(40) + (width / 1.6f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(90)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 20, 30))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(38, 48, 67))
        }
        val title = TextView(this).apply {
            text = "⋮⋮  Камеры 360°"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        val close = TextView(this).apply {
            text = "✕"
            contentDescription = "Закрыть плавающие камеры"
            setTextColor(Color.WHITE)
            textSize = 19f
            gravity = Gravity.CENTER
            setOnClickListener {
                GCamRuntimeSettings.save(this@GCamCameraOverlayService,
                    GCamRuntimeSettings.load(this@GCamCameraOverlayService).copy(cameraOverlay = false))
                stopSelf()
            }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(40)))
        setupDrag(title, root, params, dm.widthPixels, dm.heightPixels)
        root.addView(header)
        val labels = listOf("Левая", "Правая", "Перед", "Зад")
        repeat(2) { rowIndex ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(2) { columnIndex ->
                val input = rowIndex * 2 + columnIndex
                val tile = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
                val texture = TextureView(this).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                            cameraCapture.start(input, Surface(surface))
                        }
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            cameraCapture.stop(input)
                            return true
                        }
                    }
                }
                tile.addView(texture, FrameLayout.LayoutParams(-1, -1))
                tile.addView(TextView(this).apply {
                    text = labels[input]
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    setBackgroundColor(0x88000000.toInt())
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
                row.addView(tile, LinearLayout.LayoutParams(0, (width / 3.2f).toInt(), 1f).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                })
            }
            root.addView(row)
        }
        try {
            windows.addView(root, params)
            panel = root
        } catch (_: Exception) {
            cameraCapture.close()
            capture = null
            stopSelf()
        }
    }

    private fun setupDrag(handle: View, view: View, params: WindowManager.LayoutParams, screenW: Int, screenH: Int) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - touchX).toInt().coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    params.y = (startY + event.rawY - touchY).toInt().coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                    runCatching { windows.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CHANNEL_ID = "gcam_camera_overlay"
        const val NOTIFICATION_ID = 7412
    }
}

private class FloatingCameraCapture(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val surfaces = arrayOfNulls<Surface>(4)
    private var remote: IQCarCamCapture? = null
    private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(this@FloatingCameraCapture) {
                if (closed) return
                remote = IQCarCamCapture.Stub.asInterface(binder)
                for (input in 0..3) surfaces[input]?.let { queueStart(input, it) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@FloatingCameraCapture) { remote = null }
        }
    }

    init {
        appContext.bindService(Intent(appContext, QCarCamCaptureService::class.java),
            connection, Context.BIND_AUTO_CREATE)
    }

    @Synchronized fun start(input: Int, surface: Surface) {
        if (closed) { surface.release(); return }
        val old = surfaces[input]
        if (old != null) queueStop(input, old)
        surfaces[input] = surface
        queueStart(input, surface)
    }

    @Synchronized fun stop(input: Int) {
        surfaces[input]?.let { queueStop(input, it) }
        surfaces[input] = null
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        for (input in 0..3) stop(input)
        runCatching { appContext.unbindService(connection) }
        executor.shutdown()
    }

    private fun queueStart(input: Int, surface: Surface) {
        executor.execute {
            val client = synchronized(this) { if (!closed && surfaces[input] === surface) remote else null }
            if (client != null && surface.isValid) runCatching {
                client.startHudV2(6 + input, surface, input, 15, 0f, 0f, 1f, 1f, 0f, 0, 0)
            }
        }
    }

    private fun queueStop(input: Int, surface: Surface) {
        executor.execute {
            val client = synchronized(this) { remote }
            runCatching { client?.stopHud(6 + input) }
            surface.release()
        }
    }
}
