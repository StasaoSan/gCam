package com.stasao.gcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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
    private var capture: IQCarCamCapture? = null
    private var captureBound = false
    private var starting = false
    private var currentState = RecorderState()
    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            capture = IQCarCamCapture.Stub.asInterface(service)
            captureBound = true
            scope.launch { startEncoders() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
            captureBound = false
            errors[-1] = "Сервис камер отключился"
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_QUERY -> publish(currentState)
            else -> startRecording()
        }
        return if (intent?.action == ACTION_QUERY) START_NOT_STICKY else START_STICKY
    }

    private fun startRecording() {
        if (starting || encoders.isNotEmpty()) return
        starting = true
        config = RecorderSettings.load(this)
        RecordingStore.migrateLegacy(this)
        RecordingStore.removeInterrupted(this)
        RecordingStore.enforceLimit(this, config)
        if (config.cameraIds.isEmpty()) {
            publish(RecorderState(status = "Выберите хотя бы одну камеру"))
            starting = false
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, notification("Запуск камер…"))
        errors.clear()
        val intent = Intent(this, QCarCamCaptureService::class.java)
        if (!bindService(intent, captureConnection, BIND_AUTO_CREATE)) {
            starting = false
            publish(RecorderState(status = "Не удалось запустить сервис камер"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startEncoders() {
        val remote = capture ?: return
        config.cameraIds.sorted().forEach { id ->
            runCatching {
                CameraEncoder(this, id, config) { message -> errors[id] = message }.also {
                    it.start(remote); encoders[id] = it
                }
            }.onFailure { errors[id] = it.message ?: it.javaClass.simpleName }
        }
        starting = false
        if (encoders.isEmpty()) {
            val cameras = config.cameraIds.associateWith { id ->
                CameraRecordingState(id, error = errors[id] ?: "Не удалось запустить")
            }
            publish(RecorderState(cameras = cameras, usedBytes = RecordingStore.usedBytes(this), status = "Запись не запущена"))
            unbindCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        statsJob?.cancel()
        statsJob = scope.launch {
            val previous = mutableMapOf<Int, Pair<Long, Long>>()
            var usedBytes = RecordingStore.usedBytes(this@DashcamService)
            var tick = 0
            while (isActive && encoders.isNotEmpty()) {
                val now = System.nanoTime()
                val cameras = config.cameraIds.associateWith { id ->
                    val stats = runCatching { remote.stats(id) }.getOrDefault(longArrayOf())
                    val frames = stats.getOrElse(1) { 0 }
                    val old = previous[id] ?: (frames to now)
                    val fps = (frames - old.first).coerceAtLeast(0) /
                        ((now - old.second).coerceAtLeast(1) / 1_000_000_000.0)
                    previous[id] = frames to now
                    val nativeStatus = runCatching { remote.status(id) }.getOrDefault("Сервис камер недоступен")
                    val nativeError = nativeStatus.takeIf { !it.contains("ожидание", true) && !it.contains("активен", true) &&
                        !it.contains("загрузка", true) && !it.contains("запуск", true) }
                    if (tick > 0 && tick % PERF_LOG_INTERVAL_SECONDS == 0) {
                        Log.i(PERF_TAG, buildString {
                            append("input=$id mode=")
                            append(if (stats.getOrElse(16) { 0 } != 0L) "zero-copy" else "PBO")
                            append(" fps=").append("%.2f".format(fps))
                            append(" frames=").append(frames)
                            append(" timeouts=").append(stats.getOrElse(2) { 0 })
                            append(" getAvgUs=").append(stats.getOrElse(5) { 0 })
                            append(" getMaxUs=").append(stats.getOrElse(6) { 0 })
                            append(" memcpyAvgUs=").append(stats.getOrElse(7) { 0 })
                            append(" memcpyMaxUs=").append(stats.getOrElse(8) { 0 })
                            append(" gpuSubmitAvgUs=").append(stats.getOrElse(9) { 0 })
                            append(" gpuSubmitMaxUs=").append(stats.getOrElse(10) { 0 })
                            append(" swapAvgUs=").append(stats.getOrElse(11) { 0 })
                            append(" swapMaxUs=").append(stats.getOrElse(12) { 0 })
                            append(" holdAvgUs=").append(stats.getOrElse(13) { 0 })
                            append(" holdMaxUs=").append(stats.getOrElse(14) { 0 })
                            append(" maxGapUs=").append(stats.getOrElse(15) { 0 })
                        })
                    }
                    CameraRecordingState(id, frames, fps, stats.getOrElse(0) { 0 } != 0L, errors[id] ?: nativeError)
                }
                val active = cameras.values.count(CameraRecordingState::recording)
                val status = if (cameras.values.none { it.error != null }) "Запись: $active/${config.cameraIds.size} камер"
                    else "Запись с ошибками: $active/${config.cameraIds.size}"
                if (tick++ % 10 == 0) usedBytes = RecordingStore.usedBytes(this@DashcamService)
                publish(RecorderState(true, cameras, usedBytes, status))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                delay(1_000)
            }
        }
    }

    private fun stopRecording() {
        statsJob?.cancel(); statsJob = null
        encoders.values.toList().forEach { runCatching { it.stop() } }
        encoders.clear()
        starting = false
        unbindCapture()
        publish(RecorderState(usedBytes = RecordingStore.usedBytes(this)))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (encoders.isNotEmpty()) stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun unbindCapture() {
        if (captureBound) runCatching { unbindService(captureConnection) }
        captureBound = false
        capture = null
    }

    private fun publish(state: RecorderState) {
        currentState = state
        RecorderRepository.update(state)
        val cameras = state.cameras.values.sortedBy(CameraRecordingState::inputId)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_RECORDING, state.recording)
            putExtra(EXTRA_USED_BYTES, state.usedBytes)
            putExtra(EXTRA_STATUS, state.status)
            putExtra(EXTRA_CAMERA_IDS, cameras.map(CameraRecordingState::inputId).toIntArray())
            putExtra(EXTRA_FRAMES, cameras.map(CameraRecordingState::frames).toLongArray())
            putExtra(EXTRA_FPS, cameras.map(CameraRecordingState::fps).toDoubleArray())
            putExtra(EXTRA_CAMERA_RECORDING, cameras.map(CameraRecordingState::recording).toBooleanArray())
            putExtra(EXTRA_ERRORS, cameras.map { it.error.orEmpty() }.toTypedArray())
        })
    }

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
        private const val PERF_TAG = "GCAM_PERF"
        private const val PERF_LOG_INTERVAL_SECONDS = 5
        const val ACTION_START = "com.stasao.gcam.START_RECORDING"
        const val ACTION_STOP = "com.stasao.gcam.STOP_RECORDING"
        const val ACTION_QUERY = "com.stasao.gcam.QUERY_RECORDING"
        const val ACTION_STATE = "com.stasao.gcam.RECORDING_STATE"
        private const val EXTRA_RECORDING = "recording"
        private const val EXTRA_USED_BYTES = "used_bytes"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_CAMERA_IDS = "camera_ids"
        private const val EXTRA_FRAMES = "frames"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_CAMERA_RECORDING = "camera_recording"
        private const val EXTRA_ERRORS = "errors"

        fun start(context: Context) = context.startForegroundService(
            Intent(context, DashcamService::class.java).setAction(ACTION_START)
        )
        fun stop(context: Context) = context.startService(
            Intent(context, DashcamService::class.java).setAction(ACTION_STOP)
        )

        fun query(context: Context) = context.startService(
            Intent(context, DashcamService::class.java).setAction(ACTION_QUERY)
        )

        fun stateFrom(intent: Intent): RecorderState {
            val ids = intent.getIntArrayExtra(EXTRA_CAMERA_IDS) ?: intArrayOf()
            val frames = intent.getLongArrayExtra(EXTRA_FRAMES) ?: longArrayOf()
            val fps = intent.getDoubleArrayExtra(EXTRA_FPS) ?: doubleArrayOf()
            val recording = intent.getBooleanArrayExtra(EXTRA_CAMERA_RECORDING) ?: booleanArrayOf()
            val errors = intent.getStringArrayExtra(EXTRA_ERRORS) ?: emptyArray()
            val cameras = ids.mapIndexed { index, id ->
                id to CameraRecordingState(
                    id,
                    frames.getOrElse(index) { 0L },
                    fps.getOrElse(index) { 0.0 },
                    recording.getOrElse(index) { false },
                    errors.getOrNull(index)?.takeIf(String::isNotEmpty)
                )
            }.toMap()
            return RecorderState(
                intent.getBooleanExtra(EXTRA_RECORDING, false), cameras,
                intent.getLongExtra(EXTRA_USED_BYTES, 0),
                intent.getStringExtra(EXTRA_STATUS) ?: "Регистратор остановлен"
            )
        }
    }
}
