package com.stasao.gcam

import android.Manifest
import android.graphics.SurfaceTexture
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.VideoView
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stasao.gcam.ui.theme.GCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class CameraInput(val id: Int, val title: String)
private data class CameraDiagnostics(val zeroCopy: Boolean, val fps: Double, val maxGapMs: Double)
private data class LinkHealth(val receivedAt: Long = 0, val enabled: Boolean = false,
                              val connected: Boolean = false, val video: Boolean = false,
                              val error: String? = null)
private val cameraInputs = listOf(CameraInput(0, "Левая"), CameraInput(1, "Правая"), CameraInput(2, "Перед"), CameraInput(3, "Зад"))
private val LocalGCamEnglish = compositionLocalOf { false }

class MainActivity : ComponentActivity() {
    private var permissionsRequested = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    private val recorderStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DashcamService.ACTION_STATE) {
                RecorderRepository.update(DashcamService.stateFrom(intent))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { GCamTheme { GCamApp() } }
    }

    override fun onStart() {
        super.onStart()
        GCamRuntimeController.startAfterManualLaunch(this)
        requestMediaPermissions()
        ContextCompat.registerReceiver(
            this,
            recorderStateReceiver,
            IntentFilter(DashcamService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        DashcamService.query(this)
    }

    override fun onStop() {
        unregisterReceiver(recorderStateReceiver)
        super.onStop()
    }

    private fun requestMediaPermissions() {
        if (permissionsRequested) return
        permissionsRequested = true
        val media = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val saved = RecorderSettings.load(this)
        val wanted = if (saved.stampGps || saved.stampSpeed) media + arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) else media
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    internal fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GCamApp() {
    val context = LocalContext.current
    val baseDensity = LocalDensity.current
    val appVersion = remember(context) { GCamUpdater.installedVersion(context) }
    var uiScale by remember { mutableFloatStateOf(RecorderSettings.loadUiScale(context)) }
    var language by remember { mutableIntStateOf(GCamLanguage.load(context)) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var updateAvailable by remember { mutableStateOf(false) }
    val recorder by RecorderRepository.state.collectAsState()
    fun chooseLanguage(value: Int) {
        language = value
        GCamLanguage.save(context, value)
        if (recorder.recording) DashcamService.updateLanguage(context, value)
        GCamRuntimeController.updateBridgeLanguage(context, value)
        GCamRuntimeController.updateFloatingLanguage(context)
        GCamRuntimeController.updateIndicatorLanguage(context)
    }
    LaunchedEffect(Unit) {
        if (GCamUpdater.supportsUpdates(context)) {
            runCatching { GCamUpdater.latestRelease(context) }
                .onSuccess { updateAvailable = it != null }
        }
    }
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density * uiScale, baseDensity.fontScale),
        LocalGCamEnglish provides GCamLanguage.isEnglish(context, language)
    ) {
    Scaffold(topBar = { Column {
        TopAppBar(title = { Text("Камеры 360° · v$appVersion") },
            actions = {
                Box {
                    TextButton(onClick = { languageMenuExpanded = true }) {
                        Text(if (LocalGCamEnglish.current) "EN" else "RU")
                    }
                    DropdownMenu(expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false }) {
                        listOf(0 to "Системный", 1 to "Русский", 2 to "English").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                languageMenuExpanded = false
                                chooseLanguage(value)
                            })
                        }
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
        PrimaryTabRow(selectedTabIndex = tab) { listOf("Камеры и запись", "Записи", if (updateAvailable) "Обновление ●" else "Обновление").forEachIndexed { i, title ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
        } }
    } }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when (tab) {
            0 -> RecorderScreen(recorder, uiScale, language,
                onUiScaleChange = {
                    uiScale = it
                    RecorderSettings.saveUiScale(context, it)
                }, onLanguageChange = ::chooseLanguage)
            1 -> RecordingsScreen(recorder.recording, recorder.warning,
                recorder.recording && recorder.cameras.values.any { it.recording })
            else -> UpdateScreen(recorder.recording)
        }
    } }
    }
}

@Composable private fun UpdateScreen(recording: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<GCamRelease?>(null) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }
    var status by remember { mutableStateOf("Проверка обновлений…") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun check() {
        if (!GCamUpdater.supportsUpdates(context)) {
            status = "Это debug-сборка. Обновления доступны после установки release APK."
            return
        }
        busy = true
        scope.launch {
            runCatching { GCamUpdater.latestRelease(context) }
                .onSuccess { found ->
                    release = found
                    downloaded = null
                    status = if (found == null) "Установлена актуальная версия" else "Доступна версия ${found.tag}"
                }
                .onFailure { status = "Ошибка проверки: ${it.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { check() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Обновление gCam") }
        item { Text("Установлена версия ${GCamUpdater.installedVersion(context)}") }
        item { Text(status) }
        item {
            OutlinedButton(onClick = { check() }, enabled = !busy && GCamUpdater.supportsUpdates(context)) {
                Text("Проверить обновления")
            }
        }
        release?.let { current ->
            if (current.notes.isNotBlank()) item {
                Text("Что нового в ${current.tag}", style = MaterialTheme.typography.titleMedium)
                Text(current.notes)
            }
            item {
                if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else if (downloaded == null) Button(onClick = {
                    busy = true
                    progress = 0f
                    status = "Загрузка ${current.tag}…"
                    scope.launch {
                        runCatching { GCamUpdater.download(context, current) { progress = it } }
                            .onSuccess { file ->
                                downloaded = file
                                status = "APK проверен. Можно установить ${current.tag}"
                            }
                            .onFailure { status = "Ошибка загрузки: ${it.message}" }
                        busy = false
                    }
                }) { Text("Скачать обновление") }
                else Button(onClick = {
                    runCatching {
                        if (GCamUpdater.canInstall(context)) {
                            GCamUpdater.launchInstaller(context, downloaded!!)
                        } else {
                            status = "Разрешите установку из gCam, затем нажмите кнопку ещё раз"
                            GCamUpdater.openInstallPermission(context)
                        }
                    }
                        .onFailure { status = "Не удалось открыть установщик: ${it.message}" }
                }, enabled = !recording) { Text("Установить ${current.tag}") }
            }
            if (recording) item {
                Text("Идёт запись. Перед установкой завершите её, чтобы MP4-файлы закрылись корректно.")
            }
        }
        item { Text("Установку подтверждает системный экран Android. При обновлении приложение будет перезапущено.", fontSize = 12.sp) }
    }
}

@Composable private fun RecorderScreen(state: RecorderState, uiScale: Float, language: Int,
                                       onUiScaleChange: (Float) -> Unit, onLanguageChange: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var previewController by remember { mutableStateOf<MultiCameraPreviewController?>(null) }
    var externalFolderRevision by remember { mutableIntStateOf(0) }
    var driveListRevision by remember { mutableIntStateOf(0) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var showQualityWarning by remember { mutableStateOf(false) }
    var showInternalWarning by remember { mutableStateOf(false) }
    var hudHealth by remember { mutableStateOf(LinkHealth()) }
    var blindHealth by remember { mutableStateOf(LinkHealth()) }
    var recorderVideoAt by remember { mutableLongStateOf(0L) }
    var healthNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var usbError by remember { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf(RecorderSettings.load(context)) }
    LaunchedEffect(config) { RecorderSettings.save(context, config) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != GCamHealthReporter.ACTION_HEALTH) return
                val state = LinkHealth(SystemClock.elapsedRealtime(),
                    intent.getBooleanExtra(GCamHealthReporter.EXTRA_ENABLED, false),
                    intent.getBooleanExtra(GCamHealthReporter.EXTRA_HUD_CONNECTED, false),
                    intent.getBooleanExtra(GCamHealthReporter.EXTRA_VIDEO_RECEIVED, false),
                    intent.getStringExtra(GCamHealthReporter.EXTRA_ERROR))
                when (intent.getStringExtra(GCamHealthReporter.EXTRA_SOURCE)) {
                    GCamHealthReporter.SOURCE_HUD -> hudHealth = state
                    GCamHealthReporter.SOURCE_BLIND -> blindHealth = state
                    GCamHealthReporter.SOURCE_RECORDER -> if (
                        intent.getBooleanExtra(GCamHealthReporter.EXTRA_RECORDING_VIDEO, false)
                    ) recorderVideoAt = SystemClock.elapsedRealtime()
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(GCamHealthReporter.ACTION_HEALTH),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(config.storage, externalFolderRevision, driveListRevision) {
        while (true) {
            usbError = if (config.storage == RecordingStorage.EXTERNAL) withContext(Dispatchers.IO) {
                runCatching { RecordingStore.validateExternal(context) }
                    .getOrElse { it.message ?: "Ошибка USB" }
            } else null
            healthNow = SystemClock.elapsedRealtime()
            delay(3_000)
        }
    }
    val removableDrives = remember(driveListRevision, externalFolderRevision) {
        RecordingStore.removableDrives(context)
    }
    fun chooseDrive(drive: RemovableDrive) {
        RecorderSettings.saveExternalDriveId(context, drive.id)
        externalFolderRevision++
        folderError = RecordingStore.validateExternal(context)
    }
    val storage = remember(state.usedBytes, config.storage, externalFolderRevision) {
        RecordingStore.storageStats(context, config.storage)
    }
    val externalError = remember(config.storage, externalFolderRevision) {
        if (config.storage == RecordingStorage.EXTERNAL) RecordingStore.validateExternal(context) else null
    }
    val maxArchiveGb = remember(storage, config.storage) { RecordingStore.maxArchiveGb(context, config.storage) }
    val sliderMaxGb = maxArchiveGb ?: config.storageLimitGb.coerceAtLeast(40)
    val minArchiveGb = if (sliderMaxGb >= 5) 5 else 1
    LaunchedEffect(maxArchiveGb) {
        if (maxArchiveGb != null && config.storageLimitGb > maxArchiveGb)
            config = config.copy(storageLimitGb = maxArchiveGb)
    }
    var runtimeConfig by remember { mutableStateOf(GCamRuntimeSettings.load(context)) }
    var selectedOverlayPosition by remember { mutableIntStateOf(0) }
    var anhudAccess by remember { mutableStateOf(CaptureClientTrust.status(context)) }
    var blindAccess by remember { mutableStateOf(CaptureClientTrust.blindStatus(context)) }
    var anhudAccessMessage by remember { mutableStateOf<String?>(null) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var locationAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val enabled = !state.recording
    LaunchedEffect(state.recording) { if (!state.recording) RecorderRepository.update(state.copy(usedBytes = RecordingStore.usedBytes(context))) }
    LaunchedEffect(Unit) {
        while (true) {
            val nowAllowed = Settings.canDrawOverlays(context)
            if (nowAllowed && !overlayAllowed && runtimeConfig.cameraOverlay) {
                GCamRuntimeController.applyCameraOverlaySetting(context, true)
            }
            if (nowAllowed && !overlayAllowed &&
                (runtimeConfig.statusOverlay || runtimeConfig.blindStatusOverlay || runtimeConfig.hudStatusOverlay)) {
                GCamRuntimeController.applyOverlaySetting(context)
            }
            if (nowAllowed && !overlayAllowed && runtimeConfig.recordOverlay) {
                GCamRuntimeController.applyRecordOverlaySetting(context, true)
            }
            overlayAllowed = nowAllowed
            runtimeConfig = GCamRuntimeSettings.load(context)
            locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            anhudAccess = CaptureClientTrust.status(context)
            blindAccess = CaptureClientTrust.blindStatus(context)
            delay(1_000)
        }
    }
    val hours = config.storageLimitGb * 8192.0 / (config.bitrateMbps * config.cameraIds.size.coerceAtLeast(1) * 3600.0)
    fun launchRecording() {
        val storageError = if (config.storage == RecordingStorage.EXTERNAL) RecordingStore.validateExternal(context) else null
        if (storageError != null) {
            folderError = storageError
            return
        }
        val controllerToClose = previewController
        RecorderDiagnostics.record(context, "record_button_start previewActive=${controllerToClose != null} cameras=${config.cameraIds.sorted()} storage=${config.storage}")
        preparing = true
        RecorderRepository.update(RecorderRepository.state.value.copy(recording = true, status = "Подготовка записи…"))
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { controllerToClose?.close() }
                RecorderDiagnostics.record(context, "preview_closed start_service=true")
                RecorderSettings.save(context, config)
                DashcamService.start(context, config)
            }.onFailure {
                RecorderDiagnostics.record(context, "record_button_error type=${it.javaClass.simpleName} message=${it.message}")
                RecorderRepository.update(RecorderRepository.state.value.copy(recording = false,
                    status = "Не удалось запустить запись: ${it.message}"))
            }
            preparing = false
        }
    }
    if (showQualityWarning) AlertDialog(
        onDismissRequest = { showQualityWarning = false },
        title = { Text("Высокая нагрузка") },
        text = { Text("Четыре камеры при 1280×800 могут перегрузить G636 и вызвать пропуски кадров. Рекомендуется 640×400, 15 FPS и 2 Мбит/с на камеру.") },
        confirmButton = { TextButton(onClick = { showQualityWarning = false; launchRecording() }) { Text("Всё равно записывать") } },
        dismissButton = { TextButton(onClick = { showQualityWarning = false }) { Text("Назад") } }
    )
    if (showInternalWarning) AlertDialog(
        onDismissRequest = { showInternalWarning = false },
        title = { Text("Запись во внутреннюю память") },
        text = { Text("Длительная запись четырёх камер создаёт постоянную нагрузку на внутренний накопитель и расходует его ресурс. По возможности используйте USB-накопитель.") },
        confirmButton = { TextButton(onClick = {
            RecorderSettings.saveStorage(context, RecordingStorage.INTERNAL)
            config = config.copy(storage = RecordingStorage.INTERNAL)
            RecorderSettings.save(context, config)
            showInternalWarning = false
        }) { Text("Понимаю") } },
        dismissButton = { TextButton(onClick = { showInternalWarning = false }) { Text("Отмена") } }
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        state.warning?.let { warning ->
            item { RecorderWarningBanner(warning,
                state.recording && state.cameras.values.any { it.recording }) }
        }
        item {
            if (!state.recording && !preparing && !(runtimeConfig.cameraOverlay && overlayAllowed)) MultiCameraGrid(
                onMounted = { previewController = it },
                onClosed = { closed -> if (previewController === closed) previewController = null }
            )
            else if (!state.recording && !preparing) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text("Камеры показаны в плавающем окне поверх приложений.", modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
            else Surface(color = Color(0xFF1B5E20), shape = MaterialTheme.shapes.medium) {
                Text(if (preparing) "Останавливаю превью и запускаю запись…" else state.status,
                    color = Color.White, modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
        }
        item {
            val adviceState: (CaptureClientTrust.Status, LinkHealth) -> ImagePathAdvice.State = { access, health ->
                ImagePathAdvice.State(
                    installed = access.installed,
                    access = access.sameSigner || access.approved,
                    reportFresh = health.receivedAt > 0L && healthNow - health.receivedAt <= 6_000L,
                    enabled = health.enabled,
                    connected = health.connected,
                    video = health.video,
                    error = health.error,
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Почему нет изображения?", style = MaterialTheme.typography.titleMedium)
                    Text("Штатное 360° работает отдельно. Проверьте живые боковые камеры в превью выше, затем путь до нужного экрана.", fontSize = 13.sp)
                    Text("Проекция: ${ImagePathAdvice.explain("ANHUD", adviceState(anhudAccess, hudHealth), LocalGCamEnglish.current)}", fontSize = 13.sp)
                    Text("Приборка: ${ImagePathAdvice.explain("gBlind", adviceState(blindAccess, blindHealth), LocalGCamEnglish.current)}", fontSize = 13.sp)
                }
            }
        }
        item { Surface(color = if (state.recording) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(state.status, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface)
                state.cameras.values.sortedBy { it.inputId }.forEach { c ->
                    val mode = when (c.zeroCopy) { true -> "ZERO-COPY"; false -> "PBO fallback"; null -> "проверка…" }
                    val pause = if (c.maxGapMs > 0) " · макс. пауза ${c.maxGapMs.toInt()} мс" else ""
                    Text("${recorderCameraNames[c.inputId]}: $mode · ${"%.1f".format(Locale.US, c.fps)} fps$pause${c.error?.let { " · $it" } ?: ""}", fontSize = 12.sp, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        } }
        item { SectionTitle("Камеры для записи") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { cameraInputs.forEach { c ->
            FilterChip(enabled = enabled, selected = c.id in config.cameraIds, onClick = { config = config.copy(cameraIds = config.cameraIds.toMutableSet().apply { if (!add(c.id)) remove(c.id) }) }, label = { Text(c.title) }, modifier = Modifier.weight(1f))
        } } }
        item {
            SectionTitle("Рекомендуемые настройки")
            val four = config.cameraIds.size >= 3
            Text(if (four) "Для 3–4 камер: 640×400 · 15 FPS · 2 Мбит/с на камеру" else
                "Для 1–2 камер: 960×600 · 20 FPS · 3 Мбит/с на камеру", fontSize = 13.sp)
            OutlinedButton(onClick = {
                config = if (four) config.copy(width = 640, height = 400, fps = 15, bitrateMbps = 2)
                else config.copy(width = 960, height = 600, fps = 20, bitrateMbps = 3)
                RecorderSettings.save(context, config)
            }, enabled = enabled) { Text("Применить") }
        }
        item { SectionTitle("Разрешение записи") }
        item { ChoiceRow(listOf(640 to "640×400", 960 to "960×600", 1280 to "1280×800"), config.width, enabled) { width ->
            config = config.copy(width = width, height = when (width) { 640 -> 400; 1280 -> 800; else -> 600 })
        } }
        item { SectionTitle("Частота записи") }
        item { ChoiceRow(listOf(15 to "15 FPS", 20 to "20 FPS", 25 to "25 FPS"), config.fps, enabled) { config = config.copy(fps = it) } }
        item { SectionTitle("Битрейт H.264 на камеру") }
        item { ChoiceRow(listOf(2 to "2 Мбит/с", 3 to "3 Мбит/с", 4 to "4 Мбит/с", 6 to "6 Мбит/с"), config.bitrateMbps, enabled) { config = config.copy(bitrateMbps = it) } }
        if (config.cameraIds.size == 4 && config.width == 1280) item {
            Text("⚠ 4 камеры в максимальном разрешении: возможны сильная нагрузка, падение FPS и пропуски кадров.",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        item { SectionTitle("Длительность сегмента") }
        item { ChoiceRow(listOf(1 to "1 мин", 2 to "2 мин", 3 to "3 мин", 5 to "5 мин"), config.segmentMinutes, enabled) { config = config.copy(segmentMinutes = it) } }
        item {
            SectionTitle("Куда сохранять видео")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = config.storage == RecordingStorage.EXTERNAL, enabled = enabled,
                    onClick = {
                        RecorderSettings.saveStorage(context, RecordingStorage.EXTERNAL)
                        config = config.copy(storage = RecordingStorage.EXTERNAL)
                        RecorderSettings.save(context, config)
                    }, label = { Text("USB") }, modifier = Modifier.weight(1f))
                FilterChip(selected = config.storage == RecordingStorage.INTERNAL, enabled = enabled,
                    onClick = { showInternalWarning = true }, label = { Text("Внутренняя") }, modifier = Modifier.weight(1f))
            }
            if (config.storage == RecordingStorage.EXTERNAL) {
                val selectedId = remember(externalFolderRevision, driveListRevision) {
                    RecordingStore.selectedDrive(context)?.id ?: RecorderSettings.externalDriveId(context)
                }
                Text("Флешка для записи", fontSize = 14.sp)
                if (removableDrives.isEmpty()) Text("Подключённые USB-накопители не найдены", fontSize = 12.sp)
                removableDrives.forEach { drive ->
                    val selected = selectedId.equals(drive.id, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        enabled = enabled,
                        onClick = { chooseDrive(drive) },
                        label = { Text("${if (selected) "✓ ВЫБРАНА · " else "Выбрать · "}${drive.name} · ${drive.id}") }
                    )
                }
                removableDrives.firstOrNull { it.id.equals(selectedId, ignoreCase = true) }?.let { drive ->
                    Text("Запись на: ${drive.name} (${drive.id}) → Movies/gCam", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { driveListRevision++ }, enabled = enabled) {
                    Text("Обновить список флешек")
                }
                if (selectedId != null && removableDrives.none { it.id.equals(selectedId, ignoreCase = true) }) {
                    Text("Ранее выбранная флешка $selectedId сейчас не подключена", fontSize = 12.sp)
                }
                Text("Файлы будут в Movies/gCam на выбранной флешке. Системный выбор папки не требуется. При отключении USB запись не переключится на внутреннюю память.", fontSize = 12.sp)
                (folderError ?: externalError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            } else Text("Длительная запись изнашивает внутренний накопитель. Рекомендуется USB.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        item { SectionTitle("Лимит архива: ${config.storageLimitGb} ГБ${maxArchiveGb?.let { " из доступных $it ГБ" } ?: ""}")
            Slider(config.storageLimitGb.toFloat(), { config = config.copy(storageLimitGb = it.toInt()) },
                valueRange = minArchiveGb.toFloat()..sliderMaxGb.coerceAtLeast(minArchiveGb).toFloat(), enabled = enabled)
            if (storage.freeBytes >= 0) {
                if (storage.totalBytes >= 0) Text("Выбранный накопитель: занято ${formatBytes(storage.totalBytes - storage.freeBytes)} из ${formatBytes(storage.totalBytes)} · свободно ${formatBytes(storage.freeBytes)}", fontSize = 13.sp)
                else Text("Выбранный накопитель: свободно ${formatBytes(storage.freeBytes)}; полный объём система не сообщает", fontSize = 13.sp)
                Text("Записи gCam: ${formatBytes(storage.recordingBytes)} · максимум = свободно + записи − резерв 2 ГБ", fontSize = 13.sp)
            } else Text("Свободное место накопителя недоступно от системы. 512 ГБ не подставляются; лимит можно выставить после выбора доступной флешки.", fontSize = 12.sp)
            Text("Ориентировочно ${"%.1f".format(Locale.US, hours)} ч записи", fontSize = 13.sp)
        }
        item {
            ArchiveAccessCard()
        }
        item {
            SectionTitle("Метки на видео")
            RuntimeSwitchRow("Дата и время", "Поверх кадра, без перекодирования на CPU", config.stampTime, enabled) {
                config = config.copy(stampTime = it)
                RecorderSettings.save(context, config)
            }
            RuntimeSwitchRow("GPS-координаты", "Показываются только при свежем определении местоположения", config.stampGps, enabled) {
                config = config.copy(stampGps = it)
                RecorderSettings.save(context, config)
                if (it) (context as? MainActivity)?.requestLocationPermission()
            }
            RuntimeSwitchRow("Скорость GPS", "Не скорость автомобиля по CAN; зависит от GPS", config.stampSpeed, enabled) {
                config = config.copy(stampSpeed = it)
                RecorderSettings.save(context, config)
                if (it) (context as? MainActivity)?.requestLocationPermission()
            }
            if ((config.stampGps || config.stampSpeed) && !locationAllowed) {
                Text("Нет разрешения на точное местоположение: на видео будет GPS -- / -- KM/H", fontSize = 12.sp)
            }
        }
        item { SectionTitle("Размер интерфейса: ${(uiScale * 100).toInt()}%")
            Slider(uiScale, onUiScaleChange, valueRange = 1.3f..1.8f, steps = 9)
        }
        item {
            SectionTitle("Язык приложения")
            ChoiceRow(listOf(0 to "Системный", 1 to "Русский", 2 to "English"),
                language, true, onLanguageChange)
        }
        item {
            SectionTitle("Автозапуск и индикатор")
            Spacer(Modifier.height(6.dp))
            RuntimeSwitchRow(
                title = "Автозапуск gCam",
                subtitle = "Поднимать фоновую связь с ANHUD после загрузки системы",
                checked = runtimeConfig.autostart
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(
                    autostart = checked,
                    recordingAutostart = checked && runtimeConfig.recordingAutostart
                )
                GCamRuntimeSettings.save(context, runtimeConfig)
            }
            RuntimeSwitchRow(
                title = "Автозапуск записи",
                subtitle = "Сразу записывать выбранные выше камеры",
                checked = runtimeConfig.recordingAutostart,
                enabled = runtimeConfig.autostart
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(recordingAutostart = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
            }
            RuntimeSwitchRow(
                title = "Точка: gCam + gBlind",
                subtitle = "Камера приборки и запись: красный — нет связи; синий — кадр был, запись не нужна; оранжевый — запись ожидается; зелёный — пишет",
                checked = runtimeConfig.blindStatusOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(blindStatusOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else GCamRuntimeController.applyOverlaySetting(context)
            }
            RuntimeSwitchRow(
                title = "Точка: gCam + ANHUD",
                subtitle = "Камера проекции и запись: красный — нет связи; синий — кадр был, запись не нужна; оранжевый — запись ожидается; зелёный — пишет",
                checked = runtimeConfig.hudStatusOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(hudStatusOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else GCamRuntimeController.applyOverlaySetting(context)
            }
            RuntimeSwitchRow(
                title = "Точка: gCam + ANHUD + gBlind",
                subtitle = "Разделена пополам: слева проекция, справа приборка; каждая половина учитывает запись",
                checked = runtimeConfig.statusOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(statusOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    GCamRuntimeController.applyOverlaySetting(context)
                }
            }
            RuntimeSwitchRow(
                title = "Поверх остальных окон",
                subtitle = if (overlayAllowed) "Плавающее окно с четырьмя камерами; его можно перемещать" else "Нужно разрешение на показ поверх окон",
                checked = runtimeConfig.cameraOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(cameraOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    GCamRuntimeController.applyCameraOverlaySetting(context, checked)
                }
            }
            RuntimeSwitchRow(
                title = "Плавающая кнопка записи",
                subtitle = if (overlayAllowed) "Отдельная кнопка: красный — ошибка камер; синий — готово, запись выключена; оранжевый — USB не готов; зелёный — запись идёт. Нажмите для старта/остановки, перетащите для перемещения"
                    else "Нужно разрешение на показ поверх окон",
                checked = runtimeConfig.recordOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(recordOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    GCamRuntimeController.applyRecordOverlaySetting(context, checked)
                }
            }
            Text("Размер кнопки записи: ${runtimeConfig.recordOverlaySizeDp} dp", fontSize = 13.sp)
            Slider(
                value = runtimeConfig.recordOverlaySizeDp.toFloat(),
                onValueChange = { value ->
                    runtimeConfig = runtimeConfig.copy(recordOverlaySizeDp = value.toInt())
                    GCamRuntimeSettings.save(context, runtimeConfig)
                    GCamRuntimeController.updateRecordOverlay(context)
                },
                valueRange = 88f..180f,
                enabled = runtimeConfig.recordOverlay && overlayAllowed
            )
            TextButton(onClick = {
                runtimeConfig = GCamRuntimeSettings.withDefaultStatusOverlayPositions(runtimeConfig)
                GCamRuntimeSettings.save(context, runtimeConfig)
                GCamRuntimeController.updateAllOverlayPositions(context)
            }) {
                Text("Сбросить положение всех точек")
            }
            Text("Вернёт три индикатора на исходные места, не меняя их включение.", fontSize = 12.sp)
            Text("Положение плавающего элемента", fontSize = 13.sp)
            ChoiceRow(
                options = listOf(0 to "gBlind", 1 to "ANHUD", 2 to "Обе", 3 to "Запись"),
                selected = selectedOverlayPosition,
                enabled = true,
                onSelect = { selectedOverlayPosition = it }
            )
            fun selectedPosition(): Pair<Float, Float> = when (selectedOverlayPosition) {
                0 -> runtimeConfig.blindStatusOverlayX to runtimeConfig.blindStatusOverlayY
                1 -> runtimeConfig.hudStatusOverlayX to runtimeConfig.hudStatusOverlayY
                2 -> runtimeConfig.statusOverlayX to runtimeConfig.statusOverlayY
                else -> runtimeConfig.recordOverlayX to runtimeConfig.recordOverlayY
            }
            val (overlayX, overlayY) = selectedPosition()
            fun saveOverlayPosition(x: Float? = null, y: Float? = null) {
                val (currentX, currentY) = selectedPosition()
                val nextX = x ?: currentX
                val nextY = y ?: currentY
                runtimeConfig = when (selectedOverlayPosition) {
                    0 -> runtimeConfig.copy(blindStatusOverlayX = nextX, blindStatusOverlayY = nextY)
                    1 -> runtimeConfig.copy(hudStatusOverlayX = nextX, hudStatusOverlayY = nextY)
                    2 -> runtimeConfig.copy(statusOverlayX = nextX, statusOverlayY = nextY)
                    else -> runtimeConfig.copy(recordOverlayX = nextX, recordOverlayY = nextY)
                }
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (selectedOverlayPosition == 3) {
                    if (runtimeConfig.recordOverlay) GCamRuntimeController.updateRecordOverlay(context)
                } else GCamRuntimeController.updateAllOverlayPositions(context)
            }
            Text("Горизонталь: ${(overlayX * 100).toInt()}%", fontSize = 13.sp)
            Slider(
                value = overlayX,
                onValueChange = { saveOverlayPosition(x = it) },
                valueRange = 0f..1f
            )
            Text("Вертикаль: ${(overlayY * 100).toInt()}%", fontSize = 13.sp)
            Slider(
                value = overlayY,
                onValueChange = { saveOverlayPosition(y = it) },
                valueRange = 0f..1f
            )
            Text("Положение можно задать даже для выключенного элемента; включённый перемещается сразу. Точки также можно перетаскивать пальцем.", fontSize = 12.sp)
            Text("● красный — нет связи или сигнала", color = Color(0xFFF44336), fontSize = 12.sp)
            Text("● синий — сигнал получен, запись не запланирована", color = Color(0xFF2196F3), fontSize = 12.sp)
            Text("● оранжевый — сигнал получен, но ожидаемая запись не идёт", color = Color(0xFFFF9800), fontSize = 12.sp)
            Text("● зелёный — сигнал получен и запись идёт", color = Color(0xFF4CAF50), fontSize = 12.sp)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Открыть специальные возможности")
            }
            Text(
                "Спецвозможности — резервный автозапуск для головных устройств, где обычный запуск после загрузки не срабатывает.",
                fontSize = 12.sp
            )
        }
        item {
            SectionTitle("Доступ ANHUD к камерам")
            Text(
                when {
                    !anhudAccess.installed -> "ANHUD не установлен. После установки откройте эту страницу снова."
                    anhudAccess.sameSigner -> "Доступ разрешён автоматически: ANHUD подписан тем же ключом, что и gCam."
                    anhudAccess.approved -> "Доступ разрешён для установленной версии ANHUD."
                    anhudAccess.signerChanged -> "Предупреждение: подпись ANHUD изменилась. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную."
                    else -> "Предупреждение: ANHUD подписан неизвестным ключом. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную."
                },
                fontSize = 13.sp
            )
            if (anhudAccess.installed && !anhudAccess.sameSigner) {
                OutlinedButton(onClick = {
                    anhudAccessMessage = runCatching {
                        if (anhudAccess.approved) {
                            CaptureClientTrust.revoke(context)
                            "Доступ ANHUD отозван"
                        } else if (CaptureClientTrust.approve(context)) {
                            "Доступ ANHUD разрешён"
                        } else {
                            "ANHUD больше не установлен"
                        }
                    }.getOrElse { "Не удалось изменить доступ: ${it.message}" }
                    anhudAccess = CaptureClientTrust.status(context)
                }) {
                    Text(if (anhudAccess.approved) "Отозвать доступ" else "Разрешить ANHUD")
                }
            }
            anhudAccessMessage?.let { Text(it, fontSize = 12.sp) }
            Text("Разрешение привязано к подписи установленного ANHUD, а не к паролю или ключу внутри него.", fontSize = 12.sp)
        }
        item {
            SectionTitle("Доступ gBlind к камерам приборки")
            Text(when {
                !blindAccess.installed -> "gBlind не установлен"
                blindAccess.sameSigner -> "Доступ выдан автоматически: gBlind подписан тем же ключом, что и gCam."
                blindAccess.approved -> "Доступ к камерам приборки разрешён"
                blindAccess.signerChanged -> "Предупреждение: подпись gBlind изменилась. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную."
                else -> "Предупреждение: gBlind подписан неизвестным ключом. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную."
            }, fontSize = 13.sp)
            if (blindAccess.installed && !blindAccess.sameSigner) {
                OutlinedButton(onClick = {
                    if (blindAccess.approved) CaptureClientTrust.revokeBlind(context)
                    else CaptureClientTrust.approveBlind(context)
                    blindAccess = CaptureClientTrust.blindStatus(context)
                }) { Text(if (blindAccess.approved) "Отозвать доступ" else "Разрешить gBlind") }
            }
        }
        item { Button(onClick = {
            if (state.recording) DashcamService.stop(context)
            else if (config.cameraIds.size == 4 && config.width == 1280) showQualityWarning = true
            else launchRecording()
        }, enabled = !preparing && (state.recording || config.cameraIds.isNotEmpty()), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.recording) "Остановить запись" else "Начать запись")
        } }
        item {
            val linkText: (LinkHealth) -> String = { link -> when {
                !link.enabled -> "выключен"
                healthNow - link.receivedAt > 6_000 -> "нет свежего статуса"
                !link.error.isNullOrBlank() -> "ошибка: ${link.error}"
                link.connected && link.video -> "связь и кадры есть"
                link.connected -> "связь есть, кадры ожидаются"
                else -> "нет связи"
            } }
            Text("ANHUD: ${linkText(hudHealth)}", fontSize = 13.sp)
            Text("gBlind: ${linkText(blindHealth)}", fontSize = 13.sp)
            val videoRecording = state.recording && recorderVideoAt > 0 && healthNow - recorderVideoAt <= 6_000L
            val recordText = when {
                videoRecording -> "запись идёт"
                state.recording -> state.status
                config.storage == RecordingStorage.EXTERNAL && usbError != null -> "USB не готов: $usbError"
                state.status.startsWith("Запись не запущена") || state.status.startsWith("Не удалось") -> state.status
                config.storage == RecordingStorage.INTERNAL -> "запись не идёт · выбрана внутренняя память"
                else -> "запись не идёт · USB готов"
            }
            Text("Регистратор: $recordText", fontSize = 13.sp)
        }
        item {
            OutlinedButton(
                onClick = { runCatching { RecorderDiagnostics.share(context) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Поделиться диагностикой записи") }
            Text("Журнал сохраняется после перезагрузки и автоматически ограничен 2 МБ. Для разбора зависания отправьте его после поездки.", fontSize = 12.sp)
        }
    }
}

@Composable private fun RuntimeSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable private fun MultiCameraGrid(
    onMounted: (MultiCameraPreviewController) -> Unit,
    onClosed: (MultiCameraPreviewController) -> Unit
) {
    val context = LocalContext.current
    val controller = remember { MultiCameraPreviewController(context) }
    val currentOnMounted by rememberUpdatedState(onMounted)
    val currentOnClosed by rememberUpdatedState(onClosed)
    DisposableEffect(controller) {
        currentOnMounted(controller)
        onDispose { controller.close(); currentOnClosed(controller) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cameraInputs.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { input -> CameraTile(controller, input, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun CameraTile(controller: MultiCameraPreviewController, input: CameraInput, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Подключение…") }
    var diagnostics by remember { mutableStateOf<CameraDiagnostics?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { pollJob?.cancel(); controller.stop(input.id) } }
    Box(modifier.aspectRatio(1.6f).background(Color.Black)) {
        AndroidView(factory = { context -> TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    pollJob?.cancel()
                    val surface = Surface(texture)
                    pollJob = scope.launch(Dispatchers.IO) {
                        status = controller.start(surface, input.id)
                        if (!status.contains("Нет доступа", true)) {
                            var previousFrames = 0L
                            var previousTime = System.nanoTime()
                            while (true) {
                                delay(500)
                                status = controller.status(input.id)
                                val stats = controller.stats(input.id)
                                val now = System.nanoTime()
                                if (stats.size >= 17 && stats[1] > 0) {
                                    val frames = stats[1]
                                    if (frames > previousFrames) {
                                        GCamHealthReporter.send(context, GCamHealthReporter.SOURCE_CAMERA,
                                            videoReceived = true)
                                    }
                                    val elapsed = (now - previousTime).coerceAtLeast(1L) / 1_000_000_000.0
                                    val fps = if (previousFrames > 0) (frames - previousFrames).coerceAtLeast(0) / elapsed else 0.0
                                    diagnostics = CameraDiagnostics(stats[16] != 0L, fps, stats[15] / 1_000.0)
                                    previousFrames = frames
                                    previousTime = now
                                }
                            }
                        }
                    }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    pollJob?.cancel(); pollJob = null
                    controller.stop(input.id)
                    return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        } }, modifier = Modifier.fillMaxSize())
        Surface(color = Color(0xAA000000), modifier = Modifier.align(Alignment.TopStart)) {
            Column(Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
                Text(input.title, color = Color.White, fontSize = 12.sp)
                diagnostics?.let { d ->
                    Text(
                        "${if (d.zeroCopy) "ZERO-COPY" else "PBO fallback"} · ${"%.1f".format(Locale.US, d.fps)} fps · пауза ${d.maxGapMs.toInt()} мс",
                        color = if (d.zeroCopy) Color(0xFF69F0AE) else Color(0xFFFFCC80),
                        fontSize = 8.sp,
                        maxLines = 1
                    )
                }
                Text(status, color = Color.White, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

@Composable private fun Text(text: String, modifier: Modifier = Modifier,
                             color: Color = Color.Unspecified,
                             fontSize: TextUnit = TextUnit.Unspecified,
                             style: TextStyle = LocalTextStyle.current,
                             maxLines: Int = Int.MAX_VALUE) {
    val english = LocalGCamEnglish.current
    androidx.compose.material3.Text(
        text = if (english) EnglishUi.translate(text) else text,
        modifier = modifier, color = color, fontSize = fontSize,
        style = style, maxLines = maxLines
    )
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable private fun ArchiveAccessCard() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ArchiveAccess.isGranted()) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ArchiveAccess.isGranted()
        if (granted) settingsUnavailable = false
        RecorderDiagnostics.record(context, "archive_access allFilesAccess=$granted package=${context.packageName}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Автоматическая очистка архива")
        Text(if (granted) "Доступ выдан: старые записи удаляются автоматически по лимиту."
            else "Для удаления записей от предыдущих установок нужен однократный доступ ко всем файлам.", fontSize = 13.sp)
        Text("Android выдаёт доступ ко всему общему хранилищу. gCam удаляет по лимиту только свои записи в Movies/gCam выбранного накопителя; защищённые файлы сохраняются.", fontSize = 12.sp)
        if (!granted) OutlinedButton(onClick = {
            settingsUnavailable = !ArchiveAccess.openSettings(context)
        }) { Text("Разрешить автоматическую очистку") }
        if (settingsUnavailable) Text(
            "На этом ГУ нет окна выдачи доступа. Разрешение можно выдать через ADB: adb shell appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
            fontSize = 12.sp)
    }
}

@Composable private fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { (v, title) -> FilterChip(selected = selected == v, enabled = enabled, onClick = { onSelect(v) }, label = { Text(title) }, modifier = Modifier.weight(1f)) } }
}

@Composable private fun RecorderWarningBanner(warning: String, recordingActive: Boolean) {
    val context = LocalContext.current
    Surface(color = Color(0xFFFFE0B2), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Предупреждение о записи", style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF4E2600))
            Text(warning, color = Color(0xFF4E2600))
            Text(if (recordingActive) "Запись продолжена." else "Запись сейчас не идёт или восстанавливается.",
                color = Color(0xFF4E2600), fontSize = 13.sp)
            TextButton(onClick = { runCatching { RecorderDiagnostics.share(context) } }) {
                Text("Поделиться диагностикой")
            }
        }
    }
}

@Composable private fun RecordingsScreen(recording: Boolean, warning: String?, recordingActive: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<RecordingTimeline?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recordings by remember { mutableStateOf<List<RecordingFile>>(emptyList()) }
    var selecting by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<RecordingFile>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deletionMessage by remember { mutableStateOf<String?>(null) }
    val timelines = remember(recordings) { RecordingTimelines.group(recordings) }
    LaunchedEffect(revision) {
        recordings = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (revision == 0) RecordingStore.migrateLegacy(context)
            RecordingStore.listWithDurations(context)
        }
        selectedUris = selectedUris.intersect(recordings.mapTo(HashSet()) { it.uri })
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        warning?.let { message -> item { RecorderWarningBanner(message, recordingActive) } }
        item { ArchiveAccessCard() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("USB/gCam и Movies/gCam · ${timelines.size} записей / ${recordings.size} файлов · ${formatBytes(recordings.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.titleMedium)
                if (!selecting) {
                    Button(onClick = { selecting = true }, enabled = recordings.isNotEmpty()) {
                        Text("Выбрать файлы")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            selectedUris = recordings.mapTo(HashSet()) { it.uri }
                        }, enabled = !deleting && recordings.isNotEmpty()) { Text("Выбрать все") }
                        TextButton(onClick = { selectedUris = emptySet() },
                            enabled = !deleting && selectedUris.isNotEmpty()) { Text("Снять выбор") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            confirmDelete = recordings.filter { it.uri in selectedUris }
                        }, enabled = !deleting && !recording && selectedUris.isNotEmpty()) {
                            Text(if (deleting) "Удаление…" else "Удалить выбранные (${selectedUris.size})")
                        }
                        TextButton(onClick = { selecting = false; selectedUris = emptySet() },
                            enabled = !deleting) { Text("Отмена") }
                    }
                    if (recording) Text("Чтобы удалить файлы, сначала остановите запись.", fontSize = 12.sp)
                }
            }
        }
        if (timelines.isEmpty()) item { Text("Записей пока нет") }
        items(timelines, key = { it.parts.first().uri }) { timeline ->
            val key = timeline.parts.first().uri
            Card {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(
                            checked = timeline.parts.all { it.uri in selectedUris },
                            onCheckedChange = { checked ->
                                val partUris = timeline.parts.mapTo(HashSet()) { it.uri }
                                selectedUris = if (checked) selectedUris + partUris else selectedUris - partUris
                            }, enabled = !deleting)
                        Column(Modifier.weight(1f)) {
                            Text(recorderCameraNames[timeline.inputId] ?: "Камера ${timeline.inputId}")
                            Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, if (LocalGCamEnglish.current) Locale.ENGLISH else Locale.forLanguageTag("ru")).format(Date(timeline.startedAt))} · ${formatDuration(timeline.durationMs)} · ${timeline.parts.size} фрагм. · ${formatBytes(timeline.sizeBytes)}", fontSize = 12.sp)
                        }
                        TextButton(onClick = { playing = timeline }) { Text("Смотреть") }
                    }
                    TextButton(onClick = { expanded = if (key in expanded) expanded - key else expanded + key }) {
                        Text(if (key in expanded) "Скрыть файлы" else "Показать файлы")
                    }
                    if (key in expanded) timeline.parts.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Checkbox(checked = r.uri in selectedUris,
                                onCheckedChange = { checked ->
                                    selectedUris = if (checked) selectedUris + r.uri else selectedUris - r.uri
                                }, enabled = !deleting)
                            Text("${DateFormat.getTimeInstance(DateFormat.SHORT, if (LocalGCamEnglish.current) Locale.ENGLISH else Locale.forLanguageTag("ru")).format(Date(r.modifiedAt))} · ${formatDuration(r.durationMs)} · ${if (r.storage == RecordingStorage.EXTERNAL) "USB" else "внутренняя"}${if (r.protected) " · защищено" else ""}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                            if (!selecting) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { RecordingStore.setProtected(context, r, !r.protected) }
                                        }
                                        result.exceptionOrNull()?.let {
                                            deletionMessage = if (it is SecurityException) ArchiveAccessRequiredException.MESSAGE
                                                else "Не удалось изменить защиту: ${it.message}"
                                        }
                                        revision++
                                    }
                                }) { Text(if (r.protected) "Снять защиту" else "Защитить") }
                                TextButton(onClick = { confirmDelete = listOf(r) }, enabled = !recording && !deleting) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = null },
            title = { Text("Удалить выбранные файлы?") },
            text = { Text(buildString {
                append("Будут безвозвратно удалены ${targets.size} файлов (${formatBytes(targets.sumOf { it.sizeBytes })}) из gCam.")
                val protectedCount = targets.count { it.protected }
                if (protectedCount > 0) append(" В выбор входят защищённые файлы: $protectedCount.")
            }) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    deleting = true
                    scope.launch {
                        val (failed, accessDenied) = withContext(Dispatchers.IO) {
                            var denied = false
                            val remaining = targets.filter { file ->
                                RecorderRepository.state.value.recording ||
                                    !runCatching { RecordingStore.delete(context, file) }.onFailure {
                                        if (it is SecurityException) denied = true
                                        RecorderDiagnostics.record(context,
                                            "archive_manual_delete_error uri=${file.uri} type=${it.javaClass.simpleName} " +
                                                "allFilesAccess=${ArchiveAccess.isGranted()} message=${it.message}")
                                    }.getOrDefault(false)
                            }
                            remaining to denied
                        }
                        deleting = false
                        selectedUris = failed.mapTo(HashSet()) { it.uri }
                        if (failed.isEmpty()) selecting = false
                        deletionMessage = if (accessDenied) ArchiveAccessRequiredException.MESSAGE
                        else "Удалено ${targets.size - failed.size} из ${targets.size} файлов" +
                            if (failed.isNotEmpty()) ". Не удалось удалить ${failed.size}; они остались выделены." else "."
                        revision++
                    }
                }, enabled = !deleting && !recording) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } }
        )
    }
    deletionMessage?.let { message ->
        AlertDialog(onDismissRequest = { deletionMessage = null },
            title = { Text("Архив записей") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { deletionMessage = null }) { Text("ОК") } })
    }
    playing?.let { VideoPlayer(it) { playing = null } }
}

@Composable private fun VideoPlayer(timeline: RecordingTimeline, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val video = remember(timeline.parts.first().uri) { VideoView(context) }
        var partIndex by remember(timeline) { mutableIntStateOf(0) }
        var positionMs by remember(timeline) { mutableLongStateOf(0L) }
        var seeking by remember(timeline) { mutableStateOf(false) }
        var seekPreview by remember(timeline) { mutableLongStateOf(0L) }
        var playing by remember(timeline) { mutableStateOf(true) }
        var prepared by remember(timeline) { mutableStateOf(false) }
        var error by remember(timeline) { mutableStateOf<String?>(null) }
        val totalMs = timeline.durationMs.coerceAtLeast(1L)

        fun partOffset(index: Int): Long = timeline.parts.take(index).sumOf { it.durationMs }
        fun loadPart(index: Int, offsetMs: Long = 0L) {
            partIndex = index
            prepared = false
            error = null
            video.setOnPreparedListener {
                prepared = true
                if (offsetMs > 0) video.seekTo(offsetMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (playing) video.start()
            }
            video.setVideoURI(Uri.parse(timeline.parts[index].uri))
        }
        fun seekTo(absoluteMs: Long) {
            val target = absoluteMs.coerceIn(0, totalMs - 1)
            var remaining = target
            var index = 0
            while (index < timeline.parts.lastIndex && remaining >= timeline.parts[index].durationMs) {
                remaining -= timeline.parts[index].durationMs
                index++
            }
            positionMs = target
            if (index == partIndex && prepared) {
                video.seekTo(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (playing && !video.isPlaying) video.start()
            } else loadPart(index, remaining)
        }
        DisposableEffect(video, timeline) {
            video.setOnCompletionListener {
                if (partIndex < timeline.parts.lastIndex) loadPart(partIndex + 1)
                else { playing = false; positionMs = timeline.durationMs }
            }
            video.setOnErrorListener { _, what, extra ->
                error = "Ошибка воспроизведения ($what/$extra)"
                playing = false
                true
            }
            loadPart(0)
            onDispose { video.stopPlayback() }
        }
        LaunchedEffect(video, timeline) {
            while (true) {
                if (prepared && video.isPlaying && !seeking) {
                    positionMs = (partOffset(partIndex) + video.currentPosition).coerceAtMost(timeline.durationMs)
                }
                delay(250)
            }
        }
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { video }, modifier = Modifier.fillMaxSize())
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Закрыть") }
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFF202020)).padding(12.dp)) {
                Text("${partIndex + 1}/${timeline.parts.size} · ${formatDuration(if (seeking) seekPreview else positionMs)} / ${formatDuration(timeline.durationMs)}", color = Color.White)
                Slider(
                    value = (if (seeking) seekPreview else positionMs).coerceIn(0, totalMs).toFloat(),
                    onValueChange = { seeking = true; seekPreview = it.toLong() },
                    onValueChangeFinished = { seeking = false; seekTo(seekPreview) },
                    valueRange = 0f..totalMs.toFloat(),
                    enabled = timeline.durationMs > 0
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (playing) { playing = false; video.pause() }
                        else {
                            playing = true
                            if (positionMs >= timeline.durationMs) seekTo(0)
                            else if (prepared) video.start()
                        }
                    }) { Text(if (playing) "Пауза" else "Пуск") }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(Locale.US, seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f МБ".format(Locale.US, bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} КБ"
}
