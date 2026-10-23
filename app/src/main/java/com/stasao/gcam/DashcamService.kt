package com.stasao.gcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

class DashcamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val encoders = linkedMapOf<Int, CameraEncoder>()
    private val errors = ConcurrentHashMap<Int, String>()
    private var statsJob: Job? = null
    private var maintenanceJob: Job? = null
    private var startupJob: Job? = null
    private var recoveryJob: Job? = null
    @Volatile private var recoveryPending = false
    @Volatile private var recoveryStartedElapsedMs = 0L
    @Volatile private var lastRecoveryReason = ""
    private var recoveryWarning: String? = null
    private var config = RecorderConfig()
    private var stampSource: RecorderStampSource? = null
    private var capture: IQCarCamCapture? = null
    private var captureBound = false
    @Volatile private var starting = false
    private var currentState = RecorderState()
    private var recordingSessionId = 0L
    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            capture = IQCarCamCapture.Stub.asInterface(service)
            captureBound = true
            scope.launch { startEncoders() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
            errors[-1] = "Сервис камер отключился"
            RecorderDiagnostics.record(this@DashcamService, "capture_disconnected")
            if (starting || encoders.isNotEmpty()) {
                scope.launch(Dispatchers.Main) { recoverFromCameraFailure("Сервис камер отключился") }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        recoveryWarning = savedRecoveryWarning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_QUERY -> publish(currentState)
            else -> startRecording(intent)
        }
        return if (intent?.action == ACTION_STOP || intent?.action == ACTION_QUERY) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    private fun startRecording(intent: Intent?) {
        if (starting || encoders.isNotEmpty()) return
        starting = true
        recordingSessionId = SystemClock.elapsedRealtime()
        config = intent?.takeIf { it.hasExtra(EXTRA_SELECTED_CAMERAS) }?.let { request ->
            val saved = RecorderSettings.load(this)
            val width = request.getIntExtra(EXTRA_WIDTH, saved.width).takeIf { it in setOf(640, 960, 1280) } ?: saved.width
            saved.copy(
                cameraIds = (request.getIntArrayExtra(EXTRA_SELECTED_CAMERAS) ?: intArrayOf())
                    .filter { it in 0..3 }.toSet(),
                width = width,
                height = when (width) { 640 -> 400; 1280 -> 800; else -> 600 },
                fps = request.getIntExtra(EXTRA_FPS_SETTING, saved.fps).coerceIn(15, 25),
                bitrateMbps = request.getIntExtra(EXTRA_BITRATE, saved.bitrateMbps).coerceIn(2, 6),
                segmentMinutes = request.getIntExtra(EXTRA_SEGMENT_MINUTES, saved.segmentMinutes).coerceIn(1, 5),
                storageLimitGb = request.getIntExtra(EXTRA_STORAGE_LIMIT_GB, saved.storageLimitGb).coerceAtLeast(1),
                storage = runCatching { RecordingStorage.valueOf(
                    request.getStringExtra(EXTRA_STORAGE) ?: saved.storage.name) }.getOrDefault(saved.storage),
                stampTime = request.getBooleanExtra(EXTRA_STAMP_TIME, saved.stampTime),
                stampGps = request.getBooleanExtra(EXTRA_STAMP_GPS, saved.stampGps),
                stampSpeed = request.getBooleanExtra(EXTRA_STAMP_SPEED, saved.stampSpeed)
            )
        } ?: RecorderSettings.load(this)
        RecorderDiagnostics.record(this, "recording_start cameras=${config.cameraIds.sorted()} size=${config.width}x${config.height} fps=${config.fps} bitrateMbps=${config.bitrateMbps} segmentMinutes=${config.segmentMinutes} limitGb=${config.storageLimitGb} storage=${config.storage}")
        if (config.cameraIds.isEmpty()) {
            RecorderDiagnostics.record(this, "recording_rejected reason=no_cameras")
            startupFailed("Выберите хотя бы одну камеру")
            return
        }
        startForeground(NOTIFICATION_ID, notification("Запуск камер…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val waitForUsb = intent?.getBooleanExtra(EXTRA_WAIT_FOR_USB, false) == true
        publish(RecorderState(recording = true, status = if (waitForUsb && config.storage == RecordingStorage.EXTERNAL)
            "Ожидаю выбранный USB-накопитель…" else "Освобождаю место для записи…"))
        startupJob?.cancel()
        startupJob = scope.launch(Dispatchers.IO) {
            val preflight = runCatching { prepareArchive(waitForUsb) }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (!starting) return@withContext
                val maintenance = preflight.getOrElse { failure ->
                    RecorderDiagnostics.recordException(this@DashcamService,
                        "recording_rejected reason=archive_preflight type=${failure.javaClass.name} message=${failure.message}", failure)
                    startupFailed("Запись не запущена: ${failure.message ?: "ошибка накопителя"}")
                    return@withContext
                }
                RecorderDiagnostics.record(this@DashcamService,
                    "archive_preflight deleted=${maintenance.deletedFiles} usedBytes=${maintenance.usedBytes} freeBytes=${maintenance.freeBytes} ready=${maintenance.ready}")
                if (!maintenance.ready) {
                    startupFailed("Запись не запущена: недостаточно места в лимите архива или все старые файлы защищены")
                    return@withContext
                }
                errors.clear()
                val captureIntent = Intent(this@DashcamService, QCarCamCaptureService::class.java)
                val bound = runCatching { bindService(captureIntent, captureConnection, BIND_AUTO_CREATE) }
                    .onFailure { RecorderDiagnostics.recordException(this@DashcamService,
                        "recording_error reason=bind_failed type=${it.javaClass.name} message=${it.message}", it) }
                    .getOrDefault(false)
                if (bound) {
                    captureBound = true
                } else {
                    RecorderDiagnostics.record(this@DashcamService, "recording_error reason=bind_failed")
                    recoverFromCameraFailure("Не удалось запустить сервис камер")
                }
            }
        }
    }

    private suspend fun prepareArchive(waitForUsb: Boolean): ArchiveMaintenance {
        if (!waitForUsb || config.storage != RecordingStorage.EXTERNAL) {
            if (config.storage == RecordingStorage.EXTERNAL)
                RecordingStore.validateExternal(this)?.let { error(it) }
            RecordingStore.removeInterrupted(this, config.storage)
            return RecordingStore.enforceLimit(this, config)
        }
        val deadline = SystemClock.elapsedRealtime() + USB_WAIT_TIMEOUT_MS
        var lastReason: String? = null
        while (true) {
            val attempt = runCatching {
                RecordingStore.validateExternal(this)?.let { error(it) }
                RecordingStore.removeInterrupted(this, config.storage)
                RecordingStore.enforceLimit(this, config)
            }
            attempt.getOrNull()?.let {
                RecorderDiagnostics.record(this, "autostart_usb_ready")
                return it
            }
            val reason = attempt.exceptionOrNull()?.message ?: "Ошибка USB"
            if (reason != lastReason) {
                RecorderDiagnostics.record(this, "autostart_usb_wait reason=${reason.replace(' ', '_')}")
                lastReason = reason
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) error("USB не появился за 5 минут: $reason")
            withContext(Dispatchers.Main) {
                if (starting) publish(RecorderState(recording = true,
                    status = "Ожидаю выбранный USB (${(remaining / 1_000).coerceAtLeast(1)} с)…"))
            }
            delay(minOf(USB_RETRY_INTERVAL_MS, remaining))
        }
    }

    private fun startEncoders() {
        if (!starting) return
        val remote = capture ?: return
        val locationRequested = config.stampGps || config.stampSpeed
        val locationReady = locationRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            runCatching {
                startForeground(
                    NOTIFICATION_ID, notification("Запуск камер…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                true
            }.onFailure {
                RecorderDiagnostics.record(this, "location_stamp_unavailable type=${it.javaClass.simpleName}")
            }.getOrDefault(false)
        stampSource = RecorderStampSource(this, config).also { if (locationReady) it.start() }
        config.cameraIds.sorted().forEach { id ->
            runCatching {
                remote.setRecorderOverlay(id, stampSource?.currentText().orEmpty())
                CameraEncoder(this, id, config, recordingSessionId) { message -> errors[id] = message }.also {
                    it.start(remote); encoders[id] = it
                }
            }.onFailure {
                errors[id] = it.message ?: it.javaClass.simpleName
                RecorderDiagnostics.recordException(this,
                    "encoder_start_error input=$id type=${it.javaClass.name} message=${it.message}", it)
            }
        }
        starting = false
        if (encoders.isEmpty()) {
            stampSource?.stop(); stampSource = null
            RecorderDiagnostics.record(this, "recording_start_no_cameras errors=${errors.toSortedMap()}")
            scope.launch(Dispatchers.Main) { recoverFromCameraFailure("Камеры не запустились") }
            return
        }
        if (encoders.size != config.cameraIds.size) {
            val failedIds = config.cameraIds - encoders.keys
            RecorderDiagnostics.record(this, "recording_partial_start failedInputs=${failedIds.sorted()} errors=${failedIds.map { errors[it] }}")
            scope.launch(Dispatchers.Main) {
                recoverFromCameraFailure("Не запустились камеры: ${failedIds.sorted().joinToString()}")
            }
            return
        }
        if (recoveryPending) recoveryStartedElapsedMs = SystemClock.elapsedRealtime()
        statsJob?.cancel()
        statsJob = scope.launch {
            val previous = mutableMapOf<Int, Pair<Long, Long>>()
            val previousTiming = mutableMapOf<Int, LongArray>()
            val previousMux = mutableMapOf<Int, Pair<Long, Long>>()
            fun stageUs(current: LongArray, old: LongArray?, index: Int): Long {
                val total = (current.getOrElse(index) { 0 } - (old?.getOrElse(index) { 0 } ?: 0)).coerceAtLeast(0)
                val count = (current.getOrElse(index + 1) { 0 } - (old?.getOrElse(index + 1) { 0 } ?: 0)).coerceAtLeast(0)
                return if (count > 0) total / count / 1_000 else 0
            }
            var usedBytes = RecordingStore.usedBytes(this@DashcamService)
            var tick = 0
            var previousSampleElapsed = SystemClock.elapsedRealtime()
            var previousRecorderCpu = Process.getElapsedCpuTime()
            var previousCaptureCpu = runCatching { remote.runtimeStats().getOrElse(0) { 0 } }.getOrDefault(0)
            var consecutiveLowMemorySamples = 0
            var previousStamp: String? = null
            while (isActive && encoders.isNotEmpty()) {
                val stamp = stampSource?.currentText().orEmpty()
                if (stamp != previousStamp) {
                    config.cameraIds.forEach { id -> runCatching { remote.setRecorderOverlay(id, stamp) } }
                    previousStamp = stamp
                }
                val now = System.nanoTime()
                val rawStats = mutableMapOf<Int, LongArray>()
                val cameras = config.cameraIds.associateWith { id ->
                    val stats = runCatching { remote.stats(id) }.getOrDefault(longArrayOf())
                    rawStats[id] = stats
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
                            append(when (stats.getOrElse(19) { if (stats.getOrElse(16) { 0 } != 0L) 1L else 0L }) {
                                1L -> "zero-copy"
                                2L -> "direct"
                                else -> "PBO"
                            })
                            append(" fps=").append("%.2f".format(fps))
                            append(" frames=").append(frames)
                            append(" timeouts=").append(stats.getOrElse(2) { 0 })
                            append(" getAvgUs=").append(stats.getOrElse(5) { 0 })
                            append(" getMaxUs=").append(stats.getOrElse(6) { 0 })
                            append(" memcpyAvgUs=").append(stats.getOrElse(7) { 0 })
                            append(" memcpyMaxUs=").append(stats.getOrElse(8) { 0 })
                            append(" uploadAvgUs=").append(stats.getOrElse(17) { 0 })
                            append(" uploadMaxUs=").append(stats.getOrElse(18) { 0 })
                            append(" gpuSubmitAvgUs=").append(stats.getOrElse(9) { 0 })
                            append(" gpuSubmitMaxUs=").append(stats.getOrElse(10) { 0 })
                            append(" swapAvgUs=").append(stats.getOrElse(11) { 0 })
                            append(" swapMaxUs=").append(stats.getOrElse(12) { 0 })
                            append(" holdAvgUs=").append(stats.getOrElse(13) { 0 })
                            append(" holdMaxUs=").append(stats.getOrElse(14) { 0 })
                            append(" maxGapUs=").append(stats.getOrElse(15) { 0 })
                        })
                    }
                    CameraRecordingState(
                        inputId = id,
                        frames = frames,
                        fps = fps,
                        recording = stats.getOrElse(0) { 0 } != 0L,
                        error = errors[id] ?: nativeError,
                        zeroCopy = stats.getOrNull(16)?.takeIf { frames > 0 }?.let { it != 0L },
                        maxGapMs = stats.getOrElse(15) { 0 } / 1_000.0,
                        timeouts = stats.getOrElse(2) { 0 }
                    )
                }
                val active = cameras.values.count(CameraRecordingState::recording)
                val status = if (cameras.values.none { it.error != null }) "Запись: $active/${config.cameraIds.size} камер"
                    else "Запись с ошибками: $active/${config.cameraIds.size}"
                val deadEncoder = encoders.values.firstOrNull { !it.drainAlive && it.lastOutputElapsedMs > 0 }
                if (deadEncoder != null) {
                    val reason = errors[deadEncoder.inputId] ?: "Кодер камеры ${deadEncoder.inputId} остановился"
                    RecorderDiagnostics.record(this@DashcamService,
                        "recording_auto_stop reason=encoder_stopped input=${deadEncoder.inputId} error=$reason")
                    scope.launch(Dispatchers.Main) { recoverFromCameraFailure(reason) }
                    break
                }
                if (recoveryPending) {
                    if (encoders.values.all { it.outputFrames > 0 }) {
                        recoveryPending = false
                        RecorderDiagnostics.record(this@DashcamService,
                            "recording_recovered cameras=${encoders.keys.sorted()} outputFrames=${encoders.mapValues { it.value.outputFrames }}")
                        postRecoveryNotice("Запись всех камер восстановлена после сбоя: $lastRecoveryReason", recovered = true)
                    } else if (SystemClock.elapsedRealtime() - recoveryStartedElapsedMs > RECOVERY_FRAME_TIMEOUT_MS) {
                        RecorderDiagnostics.record(this@DashcamService,
                            "recording_recovery_no_frames outputFrames=${encoders.mapValues { it.value.outputFrames }}")
                        scope.launch(Dispatchers.Main) { recoverFromCameraFailure("Нет кадров после перезапуска камер") }
                        break
                    }
                }
                if (tick++ % (if (config.storage == RecordingStorage.EXTERNAL) 60 else 10) == 0) {
                    usedBytes = RecordingStore.usedBytes(this@DashcamService)
                }
                if (tick % 60 == 0 && maintenanceJob?.isActive != true) {
                    maintenanceJob = scope.launch(Dispatchers.IO) {
                        runCatching { RecordingStore.enforceLimit(this@DashcamService, config) }
                            .onFailure { RecorderDiagnostics.record(this@DashcamService,
                                "archive_maintenance_error type=${it.javaClass.simpleName} message=${it.message}") }
                    }
                }
                if (tick % DIAGNOSTIC_INTERVAL_SECONDS == 0) {
                    val elapsed = SystemClock.elapsedRealtime()
                    val windowMs = (elapsed - previousSampleElapsed).coerceAtLeast(1)
                    val recorderCpu = Process.getElapsedCpuTime()
                    val captureRuntime = runCatching { remote.runtimeStats() }.getOrDefault(longArrayOf())
                    val captureCpu = captureRuntime.getOrElse(0) { previousCaptureCpu }
                    val recorderHeap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                    val processMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    val systemMemory = ActivityManager.MemoryInfo().also {
                        getSystemService(ActivityManager::class.java).getMemoryInfo(it)
                    }
                    RecorderDiagnostics.record(this@DashcamService, buildString {
                        append("sample windowMs=$windowMs recorderCpuPct=")
                        append((recorderCpu - previousRecorderCpu).coerceAtLeast(0) * 100 / windowMs)
                        append(" qcarcamCpuPct=")
                        append((captureCpu - previousCaptureCpu).coerceAtLeast(0) * 100 / windowMs)
                        append(" recorderNativeBytes=").append(Debug.getNativeHeapAllocatedSize())
                        append(" recorderJavaBytes=").append(recorderHeap)
                        append(" qcarcamNativeBytes=").append(captureRuntime.getOrElse(1) { -1 })
                        append(" qcarcamJavaBytes=").append(captureRuntime.getOrElse(2) { -1 })
                        append(" recorderPssKb=").append(processMemory.totalPss)
                        append(" qcarcamPssKb=").append(captureRuntime.getOrElse(3) { -1 })
                        append(" recorderGraphicsKb=").append(processMemory.getMemoryStat("summary.graphics") ?: "n/a")
                        append(" qcarcamGraphicsKb=").append(captureRuntime.getOrElse(4) { -1 })
                        append(" systemAvailableBytes=").append(systemMemory.availMem)
                        append(" systemLowMemory=").append(systemMemory.lowMemory)
                        append(" thermalStatus=").append(getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1)
                        append(" freeStorageBytes=").append(filesDir.usableSpace)
                        append(" archiveBytes=").append(usedBytes)
                        config.cameraIds.sorted().forEach { id ->
                            val c = cameras[id]
                            val encoder = encoders[id]
                            val stages = rawStats[id] ?: longArrayOf()
                            val oldStages = previousTiming[id]
                            append(" | input=$id fps=").append("%.1f".format(Locale.US, c?.fps ?: 0.0))
                            append(" qFrames=").append(c?.frames ?: 0)
                            append(" timeouts=").append(c?.timeouts ?: 0)
                            append(" maxGapMs=").append(c?.maxGapMs?.toInt() ?: 0)
                            append(" outFrames=").append(encoder?.outputFrames ?: 0)
                            append(" outBytes=").append(encoder?.outputBytes ?: 0)
                            append(" outAgeMs=").append(encoder?.lastOutputElapsedMs?.let { elapsed - it } ?: -1)
                            append(" segments=").append(encoder?.completedSegments ?: 0)
                            append(" drainAlive=").append(encoder?.drainAlive ?: false)
                            append(" error=").append(c?.error?.replace(' ', '_') ?: "none")
                            if (stages.size >= 46) {
                                append(" stageUs[get/copy/upload/draw/swap/hold/sync/pboWait/map/tex/makeCurrent/release/unmap]=")
                                append((20..44 step 2).joinToString("/") { stageUs(stages, oldStages, it).toString() })
                                append(" stageSamples=").append((stages[25] - (oldStages?.getOrElse(25) { 0 } ?: 0)).coerceAtLeast(0))
                                append(" maxUs[get/upload/draw/swap]=")
                                append(stages.getOrElse(6) { 0 }).append('/')
                                append(stages.getOrElse(18) { 0 }).append('/')
                                append(stages.getOrElse(10) { 0 }).append('/')
                                append(stages.getOrElse(12) { 0 })
                            }
                            if (encoder != null) {
                                val oldMux = previousMux[id] ?: (0L to 0L)
                                val writes = (encoder.muxWriteCount - oldMux.second).coerceAtLeast(0)
                                val writeNs = (encoder.muxWriteTotalNs - oldMux.first).coerceAtLeast(0)
                                append(" muxWriteAvgUs=").append(if (writes > 0) writeNs / writes / 1_000 else 0)
                                append(" muxWriteMaxUs=").append(encoder.muxWriteMaxNs / 1_000)
                                previousMux[id] = encoder.muxWriteTotalNs to encoder.muxWriteCount
                            }
                            previousTiming[id] = stages
                        }
                    })
                    previousSampleElapsed = elapsed
                    previousRecorderCpu = recorderCpu
                    previousCaptureCpu = captureCpu
                    consecutiveLowMemorySamples = if (systemMemory.lowMemory || systemMemory.availMem < MIN_AVAILABLE_RAM_BYTES)
                        consecutiveLowMemorySamples + 1 else 0
                    if (consecutiveLowMemorySamples >= 2) {
                        RecorderDiagnostics.record(this@DashcamService,
                            "recording_auto_stop reason=low_memory availableBytes=${systemMemory.availMem}")
                        scope.launch(Dispatchers.Main) {
                            val message = "Запись остановлена: мало свободной ОЗУ. Автоперезапуск отключён для безопасности"
                            stopRecording(message)
                            postRecoveryNotice(message)
                        }
                        break
                    }
                }
                publish(RecorderState(true, cameras, usedBytes, status))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                delay(1_000)
            }
        }
    }

    private fun recoverFromCameraFailure(reason: String) {
        if (recoveryJob?.isActive == true) return
        val attemptsInWindow = noteRecoveryFailure()
        val delayMs = RECOVERY_DELAYS_MS[(attemptsInWindow - 1).coerceIn(0, RECOVERY_DELAYS_MS.lastIndex)]
        val selected = config
        val message = "Сбой записи: $reason. Перезапуск камер через ${delayMs / 1_000} с " +
            "($attemptsInWindow за 15 мин)" +
            if (attemptsInWindow > FREQUENT_RECOVERY_THRESHOLD) ". Частые сбои — отправьте диагностику" else ""
        lastRecoveryReason = reason
        RecorderDiagnostics.record(this,
            "recording_recovery_scheduled attempts15m=$attemptsInWindow delayMs=$delayMs " +
                "cameras=${selected.cameraIds.sorted()} storage=${selected.storage} reason=$reason")
        recoveryPending = true
        stopRecording(message, keepServiceAlive = true)
        postRecoveryNotice(message)
        recoveryJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            RecorderDiagnostics.record(this@DashcamService,
                "recording_recovery_start attempts15m=$attemptsInWindow")
            startRecording(recordingIntent(this@DashcamService, selected, selected.storage == RecordingStorage.EXTERNAL))
        }
    }

    private fun noteRecoveryFailure(): Int {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
        val recent = prefs.getString(RECOVERY_EVENTS, null).orEmpty().split(',')
            .mapNotNull(String::toLongOrNull)
            .filter { it <= now && now - it <= RECOVERY_WINDOW_MS }
            .takeLast(100)
            .toMutableList()
        recent += now
        val frequent = recent.size > FREQUENT_RECOVERY_THRESHOLD
        val previousWarning = recoveryWarning
        val frequentUntil = if (frequent) now + RECOVERY_WARNING_LIFETIME_MS
            else prefs.getLong(RECOVERY_FREQUENT_UNTIL, 0L)
        prefs.edit()
            .putString(RECOVERY_EVENTS, recent.joinToString(","))
            .putLong(RECOVERY_WARNING_UNTIL, now + RECOVERY_WARNING_LIFETIME_MS)
            .putLong(RECOVERY_FREQUENT_UNTIL, frequentUntil)
            .apply()
        recoveryWarning = warningText(frequentUntil > now)
        if (previousWarning != recoveryWarning) {
            RecorderDiagnostics.record(this,
                "recording_recovery_warning severity=${if (frequentUntil > now) "frequent" else "single"} " +
                    "attempts15m=${recent.size} message=${recoveryWarning}")
        }
        return recent.size
    }

    private fun savedRecoveryWarning(): String? {
        val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return if (prefs.getLong(RECOVERY_WARNING_UNTIL, 0L) > now)
            warningText(prefs.getLong(RECOVERY_FREQUENT_UNTIL, 0L) > now) else null
    }

    private fun warningText(frequent: Boolean) = if (frequent)
        "Частые сбои записи: больше 5 перезапусков за 15 минут. Проверьте видео и отправьте логи разработчику."
    else
        "Обнаружен сбой записи и перезапуск камер. Проверьте видео и отправьте логи разработчику."

    private fun startupFailed(message: String) {
        starting = false
        publish(RecorderState(status = message))
        if (recoveryPending) {
            recoveryPending = false
            RecorderDiagnostics.record(this, "recording_recovery_failed message=$message")
        }
        postRecoveryNotice(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecording(reason: String? = null, keepServiceAlive: Boolean = false) {
        RecorderDiagnostics.record(this, "recording_stop requested cameras=${encoders.keys.sorted()}")
        if (!keepServiceAlive) {
            recoveryJob?.cancel(); recoveryJob = null
            recoveryPending = false
        }
        startupJob?.cancel(); startupJob = null
        statsJob?.cancel(); statsJob = null
        stampSource?.stop(); stampSource = null
        maintenanceJob?.cancel(); maintenanceJob = null
        encoders.values.toList().forEach { encoder ->
            runCatching { encoder.stop() }.onFailure {
                RecorderDiagnostics.recordException(this,
                    "encoder_stop_error input=${encoder.inputId} type=${it.javaClass.name} message=${it.message}", it)
            }
        }
        encoders.clear()
        RecorderDiagnostics.record(this, "recording_stopped")
        starting = false
        unbindCapture()
        publish(RecorderState(usedBytes = currentState.usedBytes,
            status = reason ?: "Регистратор остановлен"))
        if (keepServiceAlive) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                notification(reason ?: "Перезапуск камер…"))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (encoders.isNotEmpty()) stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (encoders.isNotEmpty()) RecorderDiagnostics.record(this, "trim_memory level=$level")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun unbindCapture() {
        if (captureBound) runCatching { unbindService(captureConnection) }
        captureBound = false
        capture = null
    }

    private fun publish(state: RecorderState) {
        if (recoveryWarning != null && savedRecoveryWarning() == null) recoveryWarning = null
        val visibleState = state.copy(warning = recoveryWarning)
        currentState = visibleState
        RecorderRepository.update(visibleState)
        val cameras = visibleState.cameras.values.sortedBy(CameraRecordingState::inputId)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_RECORDING, visibleState.recording)
            putExtra(EXTRA_USED_BYTES, visibleState.usedBytes)
            putExtra(EXTRA_STATUS, visibleState.status)
            putExtra(EXTRA_WARNING, visibleState.warning)
            putExtra(EXTRA_CAMERA_IDS, cameras.map(CameraRecordingState::inputId).toIntArray())
            putExtra(EXTRA_FRAMES, cameras.map(CameraRecordingState::frames).toLongArray())
            putExtra(EXTRA_FPS, cameras.map(CameraRecordingState::fps).toDoubleArray())
            putExtra(EXTRA_CAMERA_RECORDING, cameras.map(CameraRecordingState::recording).toBooleanArray())
            putExtra(EXTRA_ERRORS, cameras.map { it.error.orEmpty() }.toTypedArray())
            putExtra(EXTRA_DIAGNOSTICS_AVAILABLE, cameras.map { it.zeroCopy != null }.toBooleanArray())
            putExtra(EXTRA_ZERO_COPY, cameras.map { it.zeroCopy == true }.toBooleanArray())
            putExtra(EXTRA_MAX_GAP_MS, cameras.map(CameraRecordingState::maxGapMs).toDoubleArray())
            putExtra(EXTRA_TIMEOUTS, cameras.map(CameraRecordingState::timeouts).toLongArray())
        })
        val now = SystemClock.elapsedRealtime()
        val recordingVideo = visibleState.recording && encoders.values.any {
            it.outputFrames > 0 && it.lastOutputElapsedMs > 0 && now - it.lastOutputElapsedMs <= 3_000L
        }
        GCamHealthReporter.send(
            this,
            GCamHealthReporter.SOURCE_RECORDER,
            recording = visibleState.recording,
            recordingVideo = recordingVideo,
            error = cameras.firstNotNullOfOrNull(CameraRecordingState::error)
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Видеорегистратор", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(ERROR_CHANNEL_ID, "Сбои записи", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun postRecoveryNotice(message: String, recovered: Boolean = false) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (recovered) "gCam — запись восстановлена" else "gCam — сбой записи")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, notification) }
            .onFailure { RecorderDiagnostics.record(this,
                "recovery_notification_failed type=${it.javaClass.simpleName} message=${it.message}") }
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
        private const val ERROR_CHANNEL_ID = "dashcam_recording_errors"
        private const val NOTIFICATION_ID = 360
        private const val ERROR_NOTIFICATION_ID = 361
        private const val RECOVERY_PREFS = "recorder_recovery"
        private const val RECOVERY_EVENTS = "events"
        private const val RECOVERY_WARNING_UNTIL = "warning_until"
        private const val RECOVERY_FREQUENT_UNTIL = "frequent_until"
        private const val RECOVERY_WINDOW_MS = 15 * 60_000L
        private const val RECOVERY_WARNING_LIFETIME_MS = 6 * 60 * 60_000L
        private const val FREQUENT_RECOVERY_THRESHOLD = 5
        private const val RECOVERY_FRAME_TIMEOUT_MS = 15_000L
        private val RECOVERY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)
        private const val PERF_TAG = "GCAM_PERF"
        private const val PERF_LOG_INTERVAL_SECONDS = 5
        private const val DIAGNOSTIC_INTERVAL_SECONDS = 5
        private const val MIN_AVAILABLE_RAM_BYTES = 512L * 1024 * 1024
        private const val EXTRA_SELECTED_CAMERAS = "selected_cameras"
        private const val EXTRA_WIDTH = "recording_width"
        private const val EXTRA_FPS_SETTING = "recording_fps"
        private const val EXTRA_BITRATE = "bitrate_mbps"
        private const val EXTRA_SEGMENT_MINUTES = "segment_minutes"
        private const val EXTRA_STORAGE_LIMIT_GB = "storage_limit_gb"
        private const val EXTRA_STORAGE = "storage"
        private const val EXTRA_WAIT_FOR_USB = "wait_for_usb"
        private const val USB_WAIT_TIMEOUT_MS = 5 * 60_000L
        private const val USB_RETRY_INTERVAL_MS = 3_000L
        private const val EXTRA_STAMP_TIME = "stamp_time"
        private const val EXTRA_STAMP_GPS = "stamp_gps"
        private const val EXTRA_STAMP_SPEED = "stamp_speed"
        const val ACTION_START = "com.stasao.gcam.START_RECORDING"
        const val ACTION_STOP = "com.stasao.gcam.STOP_RECORDING"
        const val ACTION_QUERY = "com.stasao.gcam.QUERY_RECORDING"
        const val ACTION_STATE = "com.stasao.gcam.RECORDING_STATE"
        private const val EXTRA_RECORDING = "recording"
        private const val EXTRA_USED_BYTES = "used_bytes"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_WARNING = "warning"
        private const val EXTRA_CAMERA_IDS = "camera_ids"
        private const val EXTRA_FRAMES = "frames"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_CAMERA_RECORDING = "camera_recording"
        private const val EXTRA_ERRORS = "errors"
        private const val EXTRA_DIAGNOSTICS_AVAILABLE = "diagnostics_available"
        private const val EXTRA_ZERO_COPY = "zero_copy"
        private const val EXTRA_MAX_GAP_MS = "max_gap_ms"
        private const val EXTRA_TIMEOUTS = "timeouts"

        private fun recordingIntent(context: Context, selected: RecorderConfig?, waitForUsb: Boolean) =
            Intent(context, DashcamService::class.java).setAction(ACTION_START).apply {
                putExtra(EXTRA_WAIT_FOR_USB, waitForUsb)
                selected?.let { config ->
                    putExtra(EXTRA_SELECTED_CAMERAS, config.cameraIds.sorted().toIntArray())
                    putExtra(EXTRA_WIDTH, config.width)
                    putExtra(EXTRA_FPS_SETTING, config.fps)
                    putExtra(EXTRA_BITRATE, config.bitrateMbps)
                    putExtra(EXTRA_SEGMENT_MINUTES, config.segmentMinutes)
                    putExtra(EXTRA_STORAGE_LIMIT_GB, config.storageLimitGb)
                    putExtra(EXTRA_STORAGE, config.storage.name)
                    putExtra(EXTRA_STAMP_TIME, config.stampTime)
                    putExtra(EXTRA_STAMP_GPS, config.stampGps)
                    putExtra(EXTRA_STAMP_SPEED, config.stampSpeed)
                }
            }

        fun start(context: Context, selected: RecorderConfig? = null, waitForUsb: Boolean = false) =
            context.startForegroundService(recordingIntent(context, selected, waitForUsb))
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
            val diagnostics = intent.getBooleanArrayExtra(EXTRA_DIAGNOSTICS_AVAILABLE) ?: booleanArrayOf()
            val zeroCopy = intent.getBooleanArrayExtra(EXTRA_ZERO_COPY) ?: booleanArrayOf()
            val maxGapMs = intent.getDoubleArrayExtra(EXTRA_MAX_GAP_MS) ?: doubleArrayOf()
            val timeouts = intent.getLongArrayExtra(EXTRA_TIMEOUTS) ?: longArrayOf()
            val cameras = ids.mapIndexed { index, id ->
                id to CameraRecordingState(
                    inputId = id,
                    frames = frames.getOrElse(index) { 0L },
                    fps = fps.getOrElse(index) { 0.0 },
                    recording = recording.getOrElse(index) { false },
                    error = errors.getOrNull(index)?.takeIf(String::isNotEmpty),
                    zeroCopy = if (diagnostics.getOrElse(index) { false }) zeroCopy.getOrElse(index) { false } else null,
                    maxGapMs = maxGapMs.getOrElse(index) { 0.0 },
                    timeouts = timeouts.getOrElse(index) { 0L }
                )
            }.toMap()
            return RecorderState(
                intent.getBooleanExtra(EXTRA_RECORDING, false), cameras,
                intent.getLongExtra(EXTRA_USED_BYTES, 0),
                intent.getStringExtra(EXTRA_STATUS) ?: "Регистратор остановлен",
                intent.getStringExtra(EXTRA_WARNING)
            )
        }
    }
}
