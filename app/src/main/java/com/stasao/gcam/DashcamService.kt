package com.stasao.gcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.Manifest
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.Process
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

class DashcamService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(RecorderHost.wrapContext(base))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val encoders = ConcurrentHashMap<Int, CameraEncoder>()
    // Native stop, MediaCodec drain and USB finalization must never block the UI.
    // One worker also prevents a replacement session starting before release completes.
    private val encoderWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "gcam-lifecycle") }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    @Volatile private var destroyed = false
    private var stopCompletion = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var stopKeepsService = false
    private var stopReason: String? = null
    private val errors = ConcurrentHashMap<Int, String>()
    private var statsJob: Job? = null
    private var maintenanceJob: Job? = null
    private var startupJob: Job? = null
    private var recoveryJob: Job? = null
    private var encoderRecoveryJob: Job? = null
    private var stageDiagnosticJob: Job? = null
    private var stageLogSampler = RecorderStageLogSampler()
    @Volatile private var diagnosticStartupEncoder: CameraEncoder? = null
    @Volatile private var recoveringEncoder: Int? = null
    @Volatile private var recoveryPending = false
    @Volatile private var recoveryStartedElapsedMs = 0L
    @Volatile private var lastRecoveryReason = ""
    private var recoveryWarning: String? = null
    private var config = RecorderConfig()
    @Volatile private var samplePool:EncodedSamplePool?=null
    @Volatile private var bufferPressureUntil=0L
    @Volatile private var bufferPressureBudget=RecorderBufferPolicy.MAX_BYTES
    private var engineCheckpointSource:RecorderEngineCheckpointSource?=null
    private var stampSource: RecorderStampSource? = null
    private var capture: IQCarCamCapture? = null
    private var captureBound = false
    @Volatile private var starting = false
    private var currentState = RecorderState()
    @Volatile private var recordingSessionId = 0L
    @Volatile private var lastCaptureSnapshot = "not_sampled"
    private var storageReceiver: BroadcastReceiver? = null
    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            capture = IQCarCamCapture.Stub.asInterface(service)
            captureBound = true
            if (starting && !destroyed) encoderWorker.execute { startEncoders() }
        }

        override fun onNullBinding(name: ComponentName?) {
            unbindCapture()
            recoveryPending = false
            startupFailed(GCamLanguage.string(this@DashcamService, R.string.gcam_the_camera_service_is_unavailable))
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
        engineCheckpointSource=RecorderEngineCheckpointSource(this) {
            if(!destroyed && !stopping && !starting && encoders.isNotEmpty()) {
                val accepted=encoders.values.count {it.requestCheckpoint()}
                encoderWorker.execute {
                    RecorderDiagnostics.record(this,"recording_checkpoint_requested reason=engine_stop channels=$accepted")
                }
            }
        }
        createChannel()
        recoveryWarning = savedRecoveryWarning()
        storageReceiver = runCatching { StorageDiagnostics.observe(this) }.onFailure {
            RecorderDiagnostics.record(this, "storage_observer_unavailable error=$it")
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_QUERY -> publish(currentState)
            ACTION_LANGUAGE -> {
                GCamLanguage.useInProcess(intent.getIntExtra(EXTRA_LANGUAGE, GCamLanguage.SYSTEM))
                if (currentState.recording) runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(currentState.status))
                }
            }
            else -> startRecording(intent)
        }
        return if (intent?.action == ACTION_STOP || intent?.action == ACTION_QUERY ||
            (intent?.action == ACTION_LANGUAGE && !currentState.recording)) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    private fun startRecording(intent: Intent?) {
        if (destroyed || stopping || starting || encoders.isNotEmpty() || recoveryJob?.isActive == true) return
        if (!RecorderHost.captureAvailable(this)) {
            recoveryPending = false
            startupFailed(GCamLanguage.string(this, R.string.gcam_the_cameras_are_managed_by_another_app))
            return
        }
        ensureStageDiagnostics()
        starting = true
        RecorderRepository.busy = true
        recordingSessionId = SystemClock.elapsedRealtime()
        lastCaptureSnapshot = "not_sampled"
        RecorderDiagnostics.record(this, "recording_environment session=$recordingSessionId ${RecorderDiagnostics.environment(this)}")
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
        startForeground(NOTIFICATION_ID, notification("Запуск камер…"), captureServiceType())
        val waitForUsb = intent?.getBooleanExtra(EXTRA_WAIT_FOR_USB, false) == true ||
            (intent == null && config.storage == RecordingStorage.EXTERNAL)
        publish(RecorderState(recording = true, status = if (config.storage == RecordingStorage.EXTERNAL)
            "Проверяю выбранный USB-накопитель…" else "Освобождаю место для записи…"))
        startupJob?.cancel()
        startupJob = scope.launch(Dispatchers.IO) {
            val preflight = runCatching { prepareArchive(waitForUsb) }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (!starting) return@withContext
                val maintenance = preflight.getOrElse { failure ->
                    RecorderDiagnostics.recordException(this@DashcamService,
                        "recording_rejected reason=archive_preflight type=${failure.javaClass.name} message=${failure.message}", failure)
                    // Retrying codecs or waiting for USB cannot grant MediaStore delete access.
                    if (failure is ArchiveAccessRequiredException) recoveryPending = false
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
        ArchiveAccess.tryGrantWithRoot(this)
        if (config.storage != RecordingStorage.EXTERNAL) {
            RecordingStore.removeInterrupted(this, config.storage)
            return RecordingStore.enforceLimit(this, config)
        }
        val tracker = UsbReadinessTracker(
            if (recoveryPending) 15_000L else USB_STABLE_MS, USB_STABLE_SAMPLES)
        val startedAt = SystemClock.elapsedRealtime()
        var nextWaitReport = startedAt + USB_WAIT_REPORT_MS
        var lastReason: String? = null
        var lastLoggedUsbReason: String? = null
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val validation = runCatching {
                RecordingStore.validateExternal(this)?.let { error(it) }
                val identity = requireNotNull(RecordingStore.selectedDrive(this)).id
                RecordingStore.probeExternalWritable(this)
                check(RecordingStore.selectedDrive(this)?.id == identity) { "USB изменился во время проверки" }
                identity
            }
            val stable = tracker.observe(SystemClock.elapsedRealtime(), validation.isSuccess, validation.getOrNull())
            if (stable) {
                val attempt = runCatching {
                    RecordingStore.removeInterrupted(this, config.storage)
                    RecordingStore.enforceLimit(this, config)
                }
                attempt.getOrNull()?.let {
                    RecorderDiagnostics.record(this,
                        "autostart_usb_ready waitMs=${SystemClock.elapsedRealtime() - startedAt} " +
                            "volume=${validation.getOrNull()} writeReadSyncDeleteProbe=ok")
                    return it
                }
                attempt.exceptionOrNull()?.let {
                    if (it is ArchiveAccessRequiredException || it is kotlinx.coroutines.CancellationException) throw it
                }
                tracker.observe(now, false)
                lastReason = attempt.exceptionOrNull()?.message ?: "Ошибка записи на USB"
            } else if (validation.isFailure) {
                lastReason = validation.exceptionOrNull()?.message ?: "USB не подключён"
            } else {
                lastReason = "Проверяю стабильность USB"
            }
            val reason = lastReason ?: "Ошибка USB"
            if (reason != lastLoggedUsbReason) {
                RecorderDiagnostics.record(this, "autostart_usb_wait reason=${reason.replace(' ', '_')}")
                if (validation.isFailure) StorageDiagnostics.capture(this, "usb_preflight:$reason")
                lastLoggedUsbReason = reason
            }
            if (!waitForUsb && validation.isFailure) error(reason)
            if (!waitForUsb && now - startedAt >= USB_MANUAL_STABILITY_TIMEOUT_MS)
                error("USB не подтвердил стабильную запись: $reason")
            if (waitForUsb && now >= nextWaitReport) {
                RecorderDiagnostics.record(this, "autostart_usb_still_waiting elapsedMs=${now - startedAt} reason=${reason.replace(' ', '_')}")
                nextWaitReport = now + USB_WAIT_REPORT_MS
            }
            withContext(Dispatchers.Main) {
                if (starting) publish(RecorderState(recording = true,
                    status = "Ожидаю выбранный USB: $reason"))
            }
            delay(USB_RETRY_INTERVAL_MS)
        }
    }

    private fun captureServiceType(): Int = if (Build.VERSION.SDK_INT >= 34)
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun newEncoder(id: Int): CameraEncoder {
        val session = recordingSessionId
        lateinit var encoder: CameraEncoder
        encoder = CameraEncoder(this, id, config, session,checkNotNull(samplePool)) { message ->
            // A retiring encoder must never overwrite the replacement's state.
            if (recordingSessionId == session && encoders[id] === encoder) errors[id] = message
        }
        return encoder
    }

    private fun startEncoder(encoder:CameraEncoder,remote:IQCarCamCapture) {
        // Diagnostic visibility only: the serial lifecycle worker remains the owner.
        diagnosticStartupEncoder=encoder
        try {encoder.start(remote)} finally {if(diagnosticStartupEncoder===encoder)diagnosticStartupEncoder=null}
    }

    private fun ensureStageDiagnostics() {
        if(stageDiagnosticJob?.isActive==true)return
        val sampler=RecorderStageLogSampler()
        stageLogSampler=sampler
        stageDiagnosticJob=scope.launch(Dispatchers.IO) {
            while(isActive && !destroyed) {
                // Local counters are sampled independently of potentially blocked capture Binder calls.
                val observed=encoders.values.toMutableList()
                diagnosticStartupEncoder?.let {if(it !in observed)observed+=it}
                val lines=sampler.sample(observed.map{it.diagnosticMetrics()})
                for(line in lines) {
                    if(!isActive || destroyed)break
                    RecorderDiagnostics.record(this@DashcamService,line)
                }
                delay(5_000)
            }
        }
    }

    private fun stopStageDiagnostics() {
        stageLogSampler.close();stageDiagnosticJob?.cancel();stageDiagnosticJob=null
    }

    private fun startEncoders() {
        if (!starting) return
        val remote = capture ?: return
        if(samplePool==null) {
            val pool=EncodedSamplePool(activeChannels=config.cameraIds)
            if(!pool.available) {
                pool.close()
                scope.launch(Dispatchers.Main) {startupFailed("Недоступен нативный буфер записи")}
                return
            }
            samplePool=pool
            updateBufferBudget()
            RecorderDiagnostics.record(this,"recording_buffer native=true requestedBytes=${RecorderBufferPolicy.MAX_BYTES} state=${pool.snapshot()}")
        }
        val locationRequested = RecorderStampSource.requiresLocation(this, config)
        val locationReady = locationRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            runCatching {
                startForeground(
                    NOTIFICATION_ID, notification("Запуск камер…"),
                    captureServiceType() or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                true
            }.onFailure {
                RecorderDiagnostics.record(this, "location_stamp_unavailable type=${it.javaClass.simpleName}")
            }.getOrDefault(false)
        stampSource = RecorderStampSource(this, config).also { if (locationReady) it.start() }
        val startedInputs = mutableSetOf<Int>()
        config.cameraIds.sorted().forEach { id ->
            if (!starting || destroyed) return@forEach
            runCatching {
                remote.setRecorderOverlay(id, stampSource?.currentText().orEmpty())
                // Retain ownership even if startup cannot detach its native target.
                val encoder = newEncoder(id)
                encoders[id] = encoder
                startEncoder(encoder,remote)
                startedInputs += id
            }.onFailure {
                errors[id] = it.message ?: it.javaClass.simpleName
                RecorderDiagnostics.recordException(this,
                    "encoder_start_error input=$id type=${it.javaClass.name} message=${it.message}", it)
            }
        }
        if (!starting || destroyed) return
        starting = false
        if (startedInputs.isEmpty()) {
            stampSource?.stop(); stampSource = null
            RecorderDiagnostics.record(this, "recording_start_no_cameras errors=${errors.toSortedMap()}")
            scope.launch(Dispatchers.Main) { recoverFromCameraFailure("Камеры не запустились") }
            return
        }
        val checkpointSession=recordingSessionId
        mainHandler.post {
            if(!destroyed && !stopping && recordingSessionId==checkpointSession && encoders.isNotEmpty())
                engineCheckpointSource?.start()
        }
        if (startedInputs.size != config.cameraIds.size) {
            val failedIds = config.cameraIds - startedInputs
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
            val stoppedCaptures = config.cameraIds.associateWith { CaptureStopTracker() }.toMutableMap()
            val previousEncoders = mutableMapOf<Int, CameraEncoder>()
            val previousTiming = mutableMapOf<Int, LongArray>()
            val previousMux = mutableMapOf<Int, Pair<Long, Long>>()
            fun stageUs(current: LongArray, old: LongArray?, index: Int): Long {
                val total = (current.getOrElse(index) { 0 } - (old?.getOrElse(index) { 0 } ?: 0)).coerceAtLeast(0)
                val count = (current.getOrElse(index + 1) { 0 } - (old?.getOrElse(index + 1) { 0 } ?: 0)).coerceAtLeast(0)
                return if (count > 0) total / count / 1_000 else 0
            }
            fun readArchiveBytes(previous: Long): Long = runCatching {
                RecordingStore.usedBytes(this@DashcamService, config.storage)
            }.getOrElse {
                RecorderDiagnostics.record(this@DashcamService, "archive_size_unavailable error=$it")
                StorageDiagnostics.capture(this@DashcamService, "archive_size:$it")
                previous
            }
            var usedBytes = readArchiveBytes(currentState.usedBytes)
            var tick = 0
            var previousSampleElapsed = SystemClock.elapsedRealtime()
            var previousRecorderCpu = Process.getElapsedCpuTime()
            var previousCaptureCpu = runCatching { remote.runtimeStats().getOrElse(0) { 0 } }.getOrDefault(0)
            var consecutiveLowMemorySamples = 0
            var missingUsbSamples = 0
            var previousStamp: String? = null
            while (isActive && !stopping && !destroyed && encoders.isNotEmpty()) {
                val stamp = stampSource?.currentText().orEmpty()
                if (stamp != previousStamp) {
                    config.cameraIds.forEach { id -> runCatching { remote.setRecorderOverlay(id, stamp) } }
                    previousStamp = stamp
                }
                val now = System.nanoTime()
                val rawStats = mutableMapOf<Int, LongArray>()
                val cameras = config.cameraIds.associateWith { id ->
                    encoders[id]?.let { encoder ->
                        if (previousEncoders.put(id, encoder) !== encoder) {
                            previous.remove(id)
                            previousTiming.remove(id)
                            previousMux.remove(id)
                            stoppedCaptures[id] = CaptureStopTracker()
                        }
                    }
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
                            append(" replacedFrames=").append(stats.getOrElse(46) { 0 })
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
                lastCaptureSnapshot = "sampleElapsedMs=${SystemClock.elapsedRealtime()} " +
                    cameras.values.sortedBy { it.inputId }.joinToString("; ") {
                        "input=${it.inputId} running=${it.recording} qFrames=${it.frames} error=${it.error}"
                    }
                val active = cameras.values.count(CameraRecordingState::recording)
                val status = recoveringEncoder?.let { recoveringCameraStatus(it) }
                    ?: if (cameras.values.none { it.error != null }) "Запись: $active/${config.cameraIds.size} камер"
                    else "Запись с ошибками: $active/${config.cameraIds.size}"
                val failedStorage = encoders.values.firstOrNull { it.storageFailed }
                if (failedStorage != null) {
                    val reason = errors[failedStorage.inputId] ?: "Ошибка записи файла"
                    scope.launch(Dispatchers.Main) {
                        if(config.storage==RecordingStorage.EXTERNAL)recoverFromStorageFailure(reason)
                        else recoverFromCameraFailure(reason)
                    }
                    break
                }
                if (config.storage == RecordingStorage.EXTERNAL && tick % USB_RECORDING_CHECK_INTERVAL_SECONDS == 0) {
                    val usbError = runCatching { RecordingStore.validateExternal(this@DashcamService) }
                        .getOrElse { "Проверка USB: ${it.message ?: it.javaClass.simpleName}" }
                    missingUsbSamples = if (usbError == null) 0 else missingUsbSamples + 1
                    if (usbError != null && missingUsbSamples >= USB_MISSING_SAMPLES_TO_RECOVER) {
                        RecorderDiagnostics.record(this@DashcamService,
                            "recording_usb_disconnected samples=$missingUsbSamples reason=$usbError")
                        scope.launch(Dispatchers.Main) { recoverFromStorageFailure(usbError) }
                        break
                    }
                }
                val deadEncoder = encoders.values.firstOrNull { it.inputId != recoveringEncoder && !it.drainAlive }
                if (deadEncoder != null) {
                    val reason = deadEncoder.failureMessage ?: errors[deadEncoder.inputId]
                        ?: "Кодер камеры ${deadEncoder.inputId} остановился"
                    RecorderDiagnostics.record(this@DashcamService,
                        "recording_encoder_stopped input=${deadEncoder.inputId} error=$reason")
                    val local = withContext(Dispatchers.Main) { recoverSingleEncoder(deadEncoder, reason) }
                    if (!local) {
                        scope.launch(Dispatchers.Main) { recoverFromCameraFailure(reason) }
                        break
                    }
                }
                val captureStopped = encoders.values.firstOrNull { encoder ->
                    encoder.inputId != recoveringEncoder && stoppedCaptures.getValue(encoder.inputId).observe(
                        SystemClock.elapsedRealtime(),
                        rawStats[encoder.inputId]?.getOrNull(0)?.let { it != 0L },
                        encoder.outputFrames,
                        SystemClock.elapsedRealtime() - encoder.lastOutputElapsedMs)
                }
                if (captureStopped != null) {
                    RecorderDiagnostics.record(this@DashcamService,
                        "recording_capture_stopped input=${captureStopped.inputId} confirmationMs=3000")
                    scope.launch(Dispatchers.Main) {
                        recoverFromCameraFailure("Поток камеры ${captureStopped.inputId} остановлен, кадры не поступают")
                    }
                    break
                }
                val stalled = encoders.values.firstOrNull {
                    it.inputId != recoveringEncoder && SystemClock.elapsedRealtime() - (it.lastOutputElapsedMs.takeIf { time -> time > 0 }
                        ?: recordingSessionId) > 30_000L && !it.hasLiveBufferedBacklog(SystemClock.elapsedRealtime())
                }
                if (stalled != null) {
                    scope.launch(Dispatchers.Main) {
                        recoverFromCameraFailure("Нет записанных кадров 30 секунд: камера ${stalled.inputId}")
                    }
                    break
                }
                if (recoveryPending) {
                    if (SystemClock.elapsedRealtime() - recoveryStartedElapsedMs >= 5_000L &&
                        encoders.values.all { it.outputFrames > 0 && it.drainAlive &&
                            SystemClock.elapsedRealtime() - it.lastOutputElapsedMs < 2_000L }) {
                        recoveryPending = false
                        RecorderDiagnostics.record(this@DashcamService,
                            "recording_recovered cameras=${encoders.keys.sorted()} outputFrames=${encoders.mapValues { it.value.outputFrames }}")
                        postRecoveryNotice("Запись всех камер восстановлена после сбоя: $lastRecoveryReason", recovered = true)
                    } else if (SystemClock.elapsedRealtime() - recoveryStartedElapsedMs > RECOVERY_FRAME_TIMEOUT_MS &&
                        encoders.values.any {!it.hasLiveBufferedBacklog(SystemClock.elapsedRealtime()) &&
                            SystemClock.elapsedRealtime()-it.lastOutputElapsedMs>RECOVERY_FRAME_TIMEOUT_MS}) {
                        RecorderDiagnostics.record(this@DashcamService,
                            "recording_recovery_no_frames outputFrames=${encoders.mapValues { it.value.outputFrames }}")
                        scope.launch(Dispatchers.Main) { recoverFromCameraFailure("Нет кадров после перезапуска камер") }
                        break
                    }
                }
                if (tick++ % (if (config.storage == RecordingStorage.EXTERNAL) 60 else 10) == 0) {
                    usedBytes = readArchiveBytes(usedBytes)
                }
                if (tick % 60 == 0 && maintenanceJob?.isActive != true) {
                    maintenanceJob = scope.launch(Dispatchers.IO) {
                        val result = runCatching { RecordingStore.enforceLimit(this@DashcamService, config) }
                        val failure = result.exceptionOrNull()
                        val maintenance = result.getOrNull()
                        if (failure != null) RecorderDiagnostics.recordException(this@DashcamService,
                            "archive_maintenance_error type=${failure.javaClass.simpleName} message=${failure.message}", failure)
                        if (maintenance != null) RecorderDiagnostics.record(this@DashcamService,
                            "archive_maintenance deleted=${maintenance.deletedFiles} usedBytes=${maintenance.usedBytes} " +
                                "freeBytes=${maintenance.freeBytes} ready=${maintenance.ready}")
                        val reason = when {
                            failure is ArchiveAccessRequiredException -> ArchiveAccessRequiredException.MESSAGE
                            maintenance?.ready == false -> "Недостаточно места в лимите архива или все старые файлы защищены"
                            else -> null
                        }
                        if (reason != null) withContext(Dispatchers.Main) {
                            if (encoders.isNotEmpty() && !recoveryPending) {
                                val message = "Запись остановлена: $reason"
                                postRecoveryNotice(message)
                                stopRecording(message)
                            }
                        }
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
                    val poolState=updateBufferBudget(systemMemory)
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
                        poolState?.let {
                            append(" bufferBudgetBytes=").append(it.budgetBytes)
                            append(" bufferAllocatedBytes=").append(it.allocatedCapacityBytes)
                            append(" bufferLeasedBytes=").append(it.leasedPayloadBytes)
                            append(" bufferLiveLeases=").append(it.liveLeases)
                        }
                        append(" thermalStatus=").append(getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1)
                        append(" internalFreeBytes=").append(filesDir.usableSpace)
                        append(" recorderFpsCap=").append(captureRuntime.getOrElse(5) { -1 })
                        append(" visibleOutputMask=").append(captureRuntime.getOrElse(6) { -1 })
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
                            append(" outAgeMs=").append(encoder?.lastOutputElapsedMs?.let(::ageMs) ?: -1)
                            append(" segments=").append(encoder?.completedSegments ?: 0)
                            append(" drainAlive=").append(encoder?.drainAlive ?: false)
                            encoder?.writerSnapshot()?.let { queue ->
                                append(" writerOperation=").append(encoder.writerOperation)
                                append(" writerOperationAgeMs=").append(ageMs(encoder.writerOperationStartedElapsedMs))
                                append(" writerQueueBytes=").append(queue.retainedBytes)
                                append(" writerQueueFrames=").append(queue.retainedSamples)
                                append(" writerQueueAgeMs=").append(queue.oldestAgeMs)
                                append(" writerPeakBytes=").append(queue.maxRetainedBytes)
                                append(" writerPeakFrames=").append(queue.maxRetainedSamples)
                                append(" writerRejected=").append(queue.rejected)
                                append(" writerQueuedDiscarded=").append(queue.discarded)
                                append(" writerStopped=").append(queue.stopped)
                            }
                            append(" operation=").append(encoder?.operation ?: "none")
                            append(" operationAgeMs=").append(encoder?.operationStartedElapsedMs?.let(::ageMs) ?: -1)
                            append(" codecFailed=").append(encoder?.codecFailed ?: false)
                            append(" failureOperation=").append(encoder?.failureOperation ?: "none")
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
                            if (stages.size >= 51) {
                                fun delta(index:Int)=(stages[index]-(oldStages?.getOrNull(index) ?: 0L)).coerceAtLeast(0L)
                                append(" sourceNoCapacityDelta=").append(delta(47))
                                append(" sourceUploadsDelta=").append(delta(48))
                                append(" acceptedOffersDelta=").append(delta(49))
                                append(" cancelledReservationsDelta=").append(delta(50))
                            }
                            if (stages.size >= 56) {
                                fun delta(index: Int) = (stages[index] - (oldStages?.getOrElse(index) { 0 } ?: 0)).coerceAtLeast(0L)
                                val copies = delta(55)
                                append(" outputThreadCpuAvgUs=").append(stageUs(stages, oldStages, 51))
                                append(" outputThreadCpuSamples=").append(delta(52))
                                append(" privateCopyWallAvgUs=").append(if (copies > 0) delta(53) / copies / 1_000 else 0)
                                append(" privateCopyThreadCpuAvgUs=").append(if (copies > 0) delta(54) / copies / 1_000 else 0)
                                append(" privateCopySamples=").append(copies)
                            }
                            if (stages.size >= 60) {
                                val phase = stages[56].toInt()
                                val name = when (phase) {
                                    0 -> "idle"
                                    1 -> "wait_sync"
                                    2 -> "draw"
                                    3 -> "swap"
                                    4 -> "finish"
                                    5 -> "detach"
                                    6 -> "bind"
                                    7 -> "fence_submit"
                                    else -> "unknown"
                                }
                                val since = stages[57]
                                // Native phase timestamps use CLOCK_MONOTONIC, like nanoTime.
                                val phaseAgeMs = if (phase == 0) 0L else if (since > 0)
                                    ((System.nanoTime() - since).coerceAtLeast(0L) / 1_000_000) else -1L
                                append(" outputPhase=").append(name)
                                append(" outputPhaseAgeMs=").append(phaseAgeMs)
                                append(" outputPhaseGeneration=").append(stages[59])
                                append(" outputLastEglError=0x").append(stages[58].toString(16))
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
                            recoverFromCameraFailure("Мало свободной ОЗУ; ожидаю освобождения памяти")
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

    private fun cameraLabel(input: Int): String = GCamLanguage.string(this, when (input) {
        0 -> R.string.gcam_left
        1 -> R.string.gcam_right
        2 -> R.string.gcam_front
        else -> R.string.gcam_rear
    })

    private fun recoveringCameraStatus(input: Int): String =
        GCamLanguage.string(this, R.string.gcam_recovering_one_camera, cameraLabel(input)) +
            " · ${(config.cameraIds.size-1).coerceAtLeast(0)}/${config.cameraIds.size}"

    /** These are shared failures, not a reason to keep retrying just one slot. */
    private fun singleEncoderSharedFailure(input:Int,remote:IQCarCamCapture):String? {
        val now=SystemClock.elapsedRealtime()
        val states=encoders.values.map {EncoderRecoveryHealth(it.inputId,it.drainAlive,it.storageFailed,
            it.codecFailed,if(it.hasLiveBufferedBacklog(now))maxOf(1,it.outputFrames) else it.outputFrames,
            if(it.hasLiveBufferedBacklog(now))it.lastEncodedElapsedMs else it.lastOutputElapsedMs)}
        val block=isolatedEncoderRecoveryBlock(input,config.cameraIds,states,
            capture===remote && remote.asBinder().isBinderAlive,SystemClock.elapsedRealtime()) ?: return null
        return when(block.reason) {
            EncoderRecoveryBlock.Reason.CAPTURE -> "Сервис камер отключился"
            EncoderRecoveryBlock.Reason.OWNERSHIP -> "Изменился состав записывающих камер"
            EncoderRecoveryBlock.Reason.STORAGE -> encoders[requireNotNull(block.inputId)]?.failureMessage ?:
                "Ошибка накопителя камеры ${block.inputId}"
            EncoderRecoveryBlock.Reason.OTHER_CHANNEL -> encoders[requireNotNull(block.inputId)]?.failureMessage ?:
                "Нет стабильной записи камеры ${block.inputId}"
        }
    }

    private class LocalEncoderOwnerChanged:IllegalStateException("Camera owner changed during isolated recovery")

    /** Only a runtime codec failure is local. USB, Binder and multi-camera failures
     * still use the existing full-session recovery path. Called on the main thread. */
    private fun recoverSingleEncoder(failed: CameraEncoder, reason: String): Boolean {
        val id = failed.inputId
        val remote = capture ?: return false
        if (destroyed || stopping || starting || recoveryPending || recoveringEncoder != null ||
            recoveryJob?.isActive == true || !failed.codecFailed || failed.storageFailed ||
            encoders[id] !== failed || config.cameraIds.size < 2 || !remote.asBinder().isBinderAlive) return false
        if(singleEncoderSharedFailure(id,remote)!=null)return false
        val session = recordingSessionId
        recordRecoverySnapshot("encoder", reason)
        StorageDiagnostics.capture(this, "encoder_recovery:$reason")
        val attempts = noteRecoveryFailure()
        lastRecoveryReason = reason
        recoveringEncoder = id
        val status = recoveringCameraStatus(id)
        publish(currentState.copy(recording = true, status = status))
        postRecoveryNotice(status)
        RecorderDiagnostics.record(this,
            "encoder_recovery_scheduled session=$session input=$id attempts15m=$attempts " +
                "failureOperation=${failed.failureOperation} reason=$reason")
        encoderRecoveryJob = scope.launch(Dispatchers.Main) {
            val recoveryScope = this
            fun active()=recoveryScope.isActive && !stopping && !destroyed &&
                recordingSessionId==session && recoveringEncoder==id
            fun sharedHealthy()=singleEncoderSharedFailure(id,remote)==null
            var failures=0
            var localAttempt=0L
            try {
                while(active()) {
                    singleEncoderSharedFailure(id,remote)?.let {error(it)}
                    localAttempt++
                    try {
                        // A previous failed start may have changed the slot to its candidate.
                        val owner=requireNotNull(encoders[id])
                        val result=CompletableDeferred<Result<CameraEncoder?>>()
                        encoderWorker.execute {
                            result.complete(runCatching {
                                replaceTrackedEncoder(encoders,id,owner,
                                    isActive={active() && sharedHealthy()},
                                    close={encoder ->
                                        encoder.stop()
                                        check(!encoder.storageFailed) {"Storage error while closing camera $id"}
                                    },
                                    create={errors.remove(id);newEncoder(id)},
                                    start={candidate ->
                                        if(!active())throw kotlinx.coroutines.CancellationException()
                                        startEncoder(candidate,remote)
                                    })
                            })
                        }
                        val replacement=result.await().getOrThrow()
                        if(!active())return@launch
                        singleEncoderSharedFailure(id,remote)?.let {error(it)}
                        if(replacement==null)throw LocalEncoderOwnerChanged()
                        replacement.failureMessage?.let {errors[id]=it}
                        val startedAt=SystemClock.elapsedRealtime()
                        RecorderDiagnostics.record(this@DashcamService,
                            "encoder_recovery_started session=$session input=$id localAttempt=$localAttempt otherInputs=${(encoders.keys-id).sorted()}")
                        while(active()) {
                            singleEncoderSharedFailure(id,remote)?.let {error(it)}
                            if(encoders[id]!==replacement)throw LocalEncoderOwnerChanged()
                            check(replacement.drainAlive && !replacement.codecFailed) {
                                replacement.failureMessage ?: "Encoder $id stopped after restart"
                            }
                            val elapsed=SystemClock.elapsedRealtime()-startedAt
                            if(elapsed>=5_000 && replacement.outputFrames>=3 &&
                                SystemClock.elapsedRealtime()-replacement.lastOutputElapsedMs<2_000) {
                                RecorderDiagnostics.record(this@DashcamService,
                                    "encoder_recovered session=$session input=$id localAttempt=$localAttempt elapsedMs=$elapsed " +
                                        "outputFrames=${replacement.outputFrames} otherInputs=${(encoders.keys-id).sorted()}")
                                postRecoveryNotice(GCamLanguage.string(this@DashcamService,
                                    R.string.gcam_one_camera_recovered,cameraLabel(id)),recovered=true)
                                return@launch
                            }
                            check(elapsed<15_000 || replacement.hasLiveBufferedBacklog(SystemClock.elapsedRealtime())) {
                                "No stable recorded frames after restarting camera $id"
                            }
                            delay(250)
                        }
                    }catch(cancelled:kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    }catch(changed:LocalEncoderOwnerChanged) {
                        throw changed
                    }catch(failure:Throwable) {
                        if(!active())return@launch
                        singleEncoderSharedFailure(id,remote)?.let {error(it)}
                        // Standalone retains its existing full-session fallback.
                        if(!RecorderHost.embedded(this@DashcamService))throw failure
                        failures=(failures+1).coerceAtMost(4)
                        val waitMs=localEncoderRetryDelayMs(failures)
                        errors[id]=failure.message ?: reason
                        withContext(Dispatchers.IO) {
                            RecorderDiagnostics.recordException(this@DashcamService,
                                "encoder_recovery_retry session=$session input=$id localAttempt=$localAttempt " +
                                    "delayMs=$waitMs retained=true fallback=none otherInputs=${(encoders.keys-id).sorted()}",failure)
                        }
                        // No lifecycle worker is held during backoff/cooldown. The same slot
                        // stays owned, including a candidate whose native start failed.
                        delay(waitMs)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                RecorderDiagnostics.recordException(this@DashcamService,
                    "encoder_recovery_failed session=$session input=$id fallback=full_session", failure)
                if (!stopping && !destroyed && recordingSessionId == session) {
                    recoveringEncoder = null
                    encoderRecoveryJob = null
                    recoverFromCameraFailure(failure.message ?: reason, recordedAttempt = attempts)
                }
            } finally {
                if (recordingSessionId == session && recoveringEncoder == id) {
                    recoveringEncoder = null
                    encoderRecoveryJob = null
                }
            }
        }
        return true
    }

    private fun recoverFromCameraFailure(reason: String, recordedAttempt: Int? = null) {
        if (destroyed || stopping || recoveryJob?.isActive == true) return
        // Do not query MediaProvider on the main thread during a possible USB hang.
        if (config.storage == RecordingStorage.EXTERNAL &&
            (encoders.values.any { it.storageFailed } || StorageRecoveryPolicy.isStorageFailure(reason))) {
            recoverFromStorageFailure(reason, recordedAttempt)
            return
        }
        recordRecoverySnapshot("camera", reason)
        StorageDiagnostics.capture(this, "camera_recovery:$reason")
        val attemptsInWindow = recordedAttempt ?: noteRecoveryFailure()
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
        val closed = stopRecording(message, keepServiceAlive = true)
        postRecoveryNotice(message)
        recoveryJob = scope.launch(Dispatchers.Main) {
            closed.await()
            delay(delayMs)
            RecorderDiagnostics.record(this@DashcamService,
                "recording_recovery_start attempts15m=$attemptsInWindow")
            var readySamples = 0
            while (isActive && readySamples < 2) {
                val memory = ActivityManager.MemoryInfo()
                getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                readySamples = if (!memory.lowMemory && memory.availMem >= 768L * 1024 * 1024)
                    readySamples + 1 else 0
                if (readySamples < 2) {
                    publish(RecorderState(status = "Ожидаю свободную память для восстановления записи…"))
                    RecorderDiagnostics.record(this@DashcamService,
                        "recording_recovery_wait availableBytes=${memory.availMem} readySamples=$readySamples")
                    delay(5_000)
                }
            }
            recoveryJob = null
            startRecording(recordingIntent(this@DashcamService, selected, selected.storage == RecordingStorage.EXTERNAL))
        }
    }

    private fun recoverFromStorageFailure(reason: String, recordedAttempt: Int? = null) {
        if (destroyed || stopping || recoveryJob?.isActive == true) return
        val selected = config
        val attempts = recordedAttempt ?: noteRecoveryFailure()
        val delayMs = StorageRecoveryPolicy.delayMs(attempts)
        val message = "USB-накопитель недоступен. Запись возобновится после его подключения."
        lastRecoveryReason = reason
        recoveryPending = true
        RecorderDiagnostics.record(this,
            "recording_usb_lost cameras=${selected.cameraIds.sorted()} attempts15m=$attempts " +
                "delayMs=$delayMs reason=$reason")
        recordRecoverySnapshot("usb", reason)
        StorageDiagnostics.capture(this, "usb_recovery:$reason")
        val closed = stopRecording(message, keepServiceAlive = true)
        postRecoveryNotice(message)
        recoveryJob = scope.launch(Dispatchers.Main) {
            closed.await()
            delay(delayMs)
            recoveryJob = null
            startRecording(recordingIntent(this@DashcamService, selected, waitForUsb = true))
        }
    }

    // Read the event timestamp before the current clock: concurrently received frames
    // must never appear to have a negative age in the diagnostic snapshot.
    private fun ageMs(timestamp: Long): Long = (SystemClock.elapsedRealtime() - timestamp).coerceAtLeast(0)

    private fun recordRecoverySnapshot(kind: String, reason: String) {
        RecorderDiagnostics.record(this, buildString {
            append("recording_recovery_snapshot kind=$kind session=$recordingSessionId reason=$reason")
            append(" capture=[$lastCaptureSnapshot]")
            encoders.toSortedMap().forEach { (id, encoder) ->
                append(" | input=$id operation=${encoder.operation}")
                append(" operationAgeMs=${ageMs(encoder.operationStartedElapsedMs)}")
                append(" outFrames=${encoder.outputFrames}")
                val diagnostic=encoder.diagnosticSnapshot()
                append(" diagInstance=").append(diagnostic.instance)
                append(" diagActive=[").append(RecorderStageLogSampler.activeDescription(diagnostic)).append(']')
                append(" outAgeMs=${ageMs(encoder.lastOutputElapsedMs)}")
                append(" drainAlive=${encoder.drainAlive} storageFailed=${encoder.storageFailed}")
                append(" codecFailed=${encoder.codecFailed} failureOperation=${encoder.failureOperation}")
            }
        })
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
            .putLong(RECOVERY_LAST_FAILURE, now)
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
        RecorderRepository.busy = false
        publish(RecorderState(status = message))
        if (recoveryPending) {
            RecorderDiagnostics.record(this, "recording_recovery_failed message=$message")
            recoverFromCameraFailure(message)
            return
        }
        postRecoveryNotice(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecording(reason: String? = null, keepServiceAlive: Boolean = false): CompletableDeferred<Unit> {
        engineCheckpointSource?.stop()
        encoderRecoveryJob?.cancel(); encoderRecoveryJob = null
        recoveringEncoder = null
        if (!keepServiceAlive) {
            recoveryJob?.cancel(); recoveryJob = null
            recoveryPending = false
        }
        if (stopping) {
            // A user stop wins over an already queued automatic restart.
            if (!keepServiceAlive) { stopKeepsService = false; stopReason = reason }
            return stopCompletion
        }
        stopping = true
        starting = false
        // Atomic intent is immediate even if local cleanup currently holds the worker.
        encoders.values.forEach {it.requestStop()}
        stopKeepsService = keepServiceAlive
        stopReason = reason
        val completed = CompletableDeferred<Unit>()
        stopCompletion = completed
        RecorderDiagnostics.record(this, "recording_stop requested cameras=${encoders.keys.sorted()}")
        startupJob?.cancel(); startupJob = null
        statsJob?.cancel(); statsJob = null
        maintenanceJob?.cancel(); maintenanceJob = null
        publish(RecorderState(usedBytes = currentState.usedBytes, status = "Завершаю запись…"))
        mainHandler.postDelayed({
            if (!completed.isCompleted && !destroyed) {
                StorageDiagnostics.capture(this, "recording_stop_waiting")
                publish(RecorderState(
                usedBytes = currentState.usedBytes,
                status = "Ожидаю завершения кодировщика или USB. Повторный запуск пока отложен."))
            }
        }, 8_000)
        encoderWorker.execute {
            stampSource?.stop(); stampSource = null
            finishStoppingEncoders(completed)
        }
        return completed
    }

    /** A detach timeout must keep the slot owned and block the next session. */
    private fun finishStoppingEncoders(completed: CompletableDeferred<Unit>) {
        val closing = encoders.values.toList()
        closing.forEach { it.requestStop() }
        val closed = closeEncoderSlots(encoders, close = { it.stop() }) { input, failure ->
            RecorderDiagnostics.recordException(this,
                "encoder_stop_error input=$input retained=true " +
                    "type=${failure.javaClass.name} message=${failure.message}", failure)
        }
        if (!closed) {
            mainHandler.postDelayed({
                if (!destroyed) publish(RecorderState(usedBytes = currentState.usedBytes,
                    status = GCamLanguage.string(this, R.string.gcam_waiting_encoder_detachment)))
                encoderWorker.execute { finishStoppingEncoders(completed) }
            }, 2_000)
            return
        }
        samplePool?.close();samplePool=null
        RecorderDiagnostics.record(this, "recording_stopped")
        mainHandler.post {
            unbindCapture()
            stopping = false
            RecorderRepository.busy = false
            if (!destroyed) {
                publish(RecorderState(usedBytes = currentState.usedBytes,
                    status = stopReason ?: "Регистратор остановлен"))
                if (stopKeepsService) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        notification(stopReason ?: "Перезапуск камер…"))
                } else {
                    stopStageDiagnostics()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            completed.complete(Unit)
        }
    }

    override fun onDestroy() {
        destroyed = true
        engineCheckpointSource?.stop();engineCheckpointSource=null
        stopStageDiagnostics()
        storageReceiver?.let { runCatching { unregisterReceiver(it) } }
        storageReceiver = null
        if (!stopping && (starting || encoders.isNotEmpty() || captureBound)) stopRecording()
        scope.cancel()
        // Native detach can require a retry; keep its worker until all targets close.
        if (stopping) stopCompletion.invokeOnCompletion { encoderWorker.shutdown() }
        else encoderWorker.shutdown()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val cap=RecorderBufferPolicy.trimBudget(level)
        if(cap<RecorderBufferPolicy.MAX_BYTES) {
            bufferPressureBudget=cap
            bufferPressureUntil=SystemClock.elapsedRealtime()+30_000L
            samplePool?.let {it.setBudget(cap);it.trim(0)}
        }
        if (encoders.isNotEmpty()) RecorderDiagnostics.record(this, "trim_memory level=$level")
    }

    private fun updateBufferBudget(memory:ActivityManager.MemoryInfo?=null):EncodedSamplePool.Snapshot? {
        val pool=samplePool ?: return null
        val info=memory ?: ActivityManager.MemoryInfo().also {
            getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val state=pool.snapshot()
        val pressure=if(SystemClock.elapsedRealtime()<bufferPressureUntil)bufferPressureBudget else RecorderBufferPolicy.MAX_BYTES
        pool.setBudget(minOf(pressure,RecorderBufferPolicy.budget(info.availMem,state.leasedPayloadBytes,info.lowMemory)))
        if(info.lowMemory)pool.trim(0)
        return pool.snapshot()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun unbindCapture() {
        if (captureBound) runCatching { unbindService(captureConnection) }
        captureBound = false
        capture = null
    }

    private fun publish(state: RecorderState) {
        if (recoveryWarning != null && savedRecoveryWarning() == null) recoveryWarning = null
        val warningAt = if (recoveryWarning == null) 0L else {
            val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            prefs.getLong(RECOVERY_LAST_FAILURE, 0L).takeIf { it > 0 }
                ?: prefs.getString(RECOVERY_EVENTS, null).orEmpty().split(',').lastOrNull()?.toLongOrNull() ?: 0L
        }
        val visibleState = state.copy(warning = recoveryWarning, warningAtMs = warningAt)
        currentState = visibleState
        RecorderRepository.update(visibleState)
        val cameras = visibleState.cameras.values.sortedBy(CameraRecordingState::inputId)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_RECORDING, visibleState.recording)
            putExtra(EXTRA_USED_BYTES, visibleState.usedBytes)
            putExtra(EXTRA_STATUS, visibleState.status)
            putExtra(EXTRA_WARNING, visibleState.warning)
            putExtra(EXTRA_WARNING_AT, visibleState.warningAtMs)
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
            NotificationChannel(CHANNEL_ID,
                GCamLanguage.string(this, R.string.gcam_dashcam), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(ERROR_CHANNEL_ID,
                GCamLanguage.string(this, R.string.gcam_recording_failures), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun postRecoveryNotice(message: String, recovered: Boolean = false) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.mipmap.gcam_ic_launcher)
            .setContentTitle(if (recovered)
                GCamLanguage.string(this, R.string.gcam_gcam_recording_recovered)
                else GCamLanguage.string(this, R.string.gcam_gcam_recording_failure))
            .setContentText(GCamLanguage.display(this, message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(GCamLanguage.display(this, message)))
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
            .setSmallIcon(R.mipmap.gcam_ic_launcher)
            .setContentTitle(GCamLanguage.string(this, R.string.gcam_gcam_360_cameras))
            .setContentText(GCamLanguage.display(this, text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, GCamLanguage.string(this, R.string.gcam_stop), stop)
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
        private const val RECOVERY_LAST_FAILURE = "last_failure"
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
        private const val USB_WAIT_REPORT_MS = 5 * 60_000L
        private const val USB_STABLE_MS = 6_000L
        private const val USB_STABLE_SAMPLES = 3
        private const val USB_MANUAL_STABILITY_TIMEOUT_MS = 15_000L
        private const val USB_RECORDING_CHECK_INTERVAL_SECONDS = 3
        private const val USB_MISSING_SAMPLES_TO_RECOVER = 2
        private const val USB_RETRY_INTERVAL_MS = 3_000L
        private const val EXTRA_STAMP_TIME = "stamp_time"
        private const val EXTRA_STAMP_GPS = "stamp_gps"
        private const val EXTRA_STAMP_SPEED = "stamp_speed"
        const val ACTION_START = "com.stasao.gcam.START_RECORDING"
        const val ACTION_STOP = "com.stasao.gcam.STOP_RECORDING"
        const val ACTION_QUERY = "com.stasao.gcam.QUERY_RECORDING"
        private const val ACTION_LANGUAGE = "com.stasao.gcam.RECORDER_LANGUAGE"
        private const val EXTRA_LANGUAGE = "language"
        const val ACTION_STATE = "com.stasao.gcam.RECORDING_STATE"
        private const val EXTRA_RECORDING = "recording"
        private const val EXTRA_USED_BYTES = "used_bytes"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_WARNING = "warning"
        private const val EXTRA_WARNING_AT = "warning_at"
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

        fun updateLanguage(context: Context, language: Int) = context.startService(
            Intent(context, DashcamService::class.java).setAction(ACTION_LANGUAGE)
                .putExtra(EXTRA_LANGUAGE, language)
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
                intent.getStringExtra(EXTRA_WARNING),
                intent.getLongExtra(EXTRA_WARNING_AT, 0L)
            )
        }
    }
}
