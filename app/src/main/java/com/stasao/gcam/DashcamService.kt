package com.stasao.gcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DashcamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val encoders = linkedMapOf<Int, CameraEncoder>()
    private val errors = ConcurrentHashMap<Int, String>()
    private var statsJob: Job? = null
    private var config = RecorderConfig()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (encoders.isNotEmpty()) return
        config = RecorderSettings.load(this)
        RecordingStore.removeInterrupted(this)
        RecordingStore.enforceLimit(this, config)
        if (config.cameraIds.isEmpty()) {
            RecorderRepository.update(RecorderState(status = "Выберите хотя бы одну камеру"))
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, notification("Запуск камер…"))
        errors.clear()
        config.cameraIds.sorted().forEach { id ->
            runCatching {
                CameraEncoder(this, id, config) { message -> errors[id] = message }.also {
                    it.start(); encoders[id] = it
                }
            }.onFailure { errors[id] = it.message ?: it.javaClass.simpleName }
        }
        statsJob?.cancel()
        statsJob = scope.launch {
            val previous = mutableMapOf<Int, Pair<Long, Long>>()
            var usedBytes = RecordingStore.usedBytes(this@DashcamService)
            var tick = 0
            while (isActive && encoders.isNotEmpty()) {
                val now = System.nanoTime()
                val cameras = config.cameraIds.associateWith { id ->
                    val stats = NativeQCarCam.recorderStats(id)
                    val frames = stats.getOrElse(1) { 0 }
                    val old = previous[id] ?: (frames to now)
                    val fps = (frames - old.first).coerceAtLeast(0) /
                        ((now - old.second).coerceAtLeast(1) / 1_000_000_000.0)
                    previous[id] = frames to now
                    val nativeStatus = NativeQCarCam.recorderStatus(id)
                    val nativeError = nativeStatus.takeIf { !it.contains("ожидание", true) && !it.contains("активен", true) &&
                        !it.contains("загрузка", true) && !it.contains("запуск", true) }
                    CameraRecordingState(id, frames, fps, stats.getOrElse(0) { 0 } != 0L, errors[id] ?: nativeError)
                }
                val active = cameras.values.count(CameraRecordingState::recording)
                val status = if (cameras.values.none { it.error != null }) "Запись: $active/${config.cameraIds.size} камер"
                    else "Запись с ошибками: $active/${config.cameraIds.size}"
                if (tick++ % 10 == 0) usedBytes = RecordingStore.usedBytes(this@DashcamService)
                RecorderRepository.update(RecorderState(true, cameras, usedBytes, status))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                delay(1_000)
            }
        }
    }

    private fun stopRecording() {
        statsJob?.cancel(); statsJob = null
        encoders.values.toList().forEach { runCatching { it.stop() } }
        encoders.clear()
        RecorderRepository.update(RecorderState(usedBytes = RecordingStore.usedBytes(this)))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (encoders.isNotEmpty()) stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Видеорегистратор", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DashcamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("gCam — камеры 360°")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dashcam_recording"
        private const val NOTIFICATION_ID = 360
        const val ACTION_START = "com.stasao.gcam.START_RECORDING"
        const val ACTION_STOP = "com.stasao.gcam.STOP_RECORDING"

        fun start(context: Context) = context.startForegroundService(
            Intent(context, DashcamService::class.java).setAction(ACTION_START)
        )
        fun stop(context: Context) = context.startService(
            Intent(context, DashcamService::class.java).setAction(ACTION_STOP)
        )
    }
}
