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
import android.content.pm.PackageManager
import android.widget.MediaController
import android.widget.VideoView
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.unit.Density
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class CameraInput(val id: Int, val title: String)
private val cameraInputs = listOf(CameraInput(0, "Левая"), CameraInput(1, "Правая"), CameraInput(2, "Перед"), CameraInput(3, "Зад"))

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
        val wanted = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GCamApp() {
    val context = LocalContext.current
    val baseDensity = LocalDensity.current
    var uiScale by remember { mutableFloatStateOf(RecorderSettings.loadUiScale(context)) }
    var tab by remember { mutableIntStateOf(0) }
    val recorder by RecorderRepository.state.collectAsState()
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density * uiScale, baseDensity.fontScale)) {
    Scaffold(topBar = { Column {
        TopAppBar(title = { Text("Камеры 360°") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
        PrimaryTabRow(selectedTabIndex = tab) { listOf("Камеры и запись", "Записи").forEachIndexed { i, title ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
        } }
    } }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when (tab) {
            0 -> RecorderScreen(recorder, uiScale) {
                uiScale = it
                RecorderSettings.saveUiScale(context, it)
            }
            else -> RecordingsScreen()
        }
    } }
    }
}

@Composable private fun RecorderScreen(state: RecorderState, uiScale: Float, onUiScaleChange: (Float) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val storage = remember(state.usedBytes) { RecordingStore.storageStats(context) }
    val maxArchiveGb = remember(storage) { RecordingStore.maxArchiveGb(context) }
    val minArchiveGb = if (maxArchiveGb >= 5) 5 else 1
    var config by remember { mutableStateOf(RecorderSettings.load(context).let {
        it.copy(storageLimitGb = it.storageLimitGb.coerceIn(minArchiveGb, maxArchiveGb.coerceAtLeast(minArchiveGb)))
    }) }
    val enabled = !state.recording
    LaunchedEffect(state.recording) { if (!state.recording) RecorderRepository.update(state.copy(usedBytes = RecordingStore.usedBytes(context))) }
    val hours = config.storageLimitGb * 8192.0 / (config.bitrateMbps * config.cameraIds.size.coerceAtLeast(1) * 3600.0)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            if (!state.recording && !preparing) MultiCameraGrid()
            else Surface(color = Color(0xFF1B5E20), shape = MaterialTheme.shapes.medium) {
                Text(if (preparing) "Останавливаю превью и запускаю запись…" else state.status,
                    color = Color.White, modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
        }
        item { Surface(color = if (state.recording) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(state.status, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface)
                state.cameras.values.sortedBy { it.inputId }.forEach { c -> Text("${recorderCameraNames[c.inputId]}: ${"%.1f".format(Locale.US, c.fps)} fps${c.error?.let { " · $it" } ?: ""}", fontSize = 12.sp, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface) }
            }
        } }
        item { SectionTitle("Камеры для записи") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { cameraInputs.forEach { c ->
            FilterChip(enabled = enabled, selected = c.id in config.cameraIds, onClick = { config = config.copy(cameraIds = config.cameraIds.toMutableSet().apply { if (!add(c.id)) remove(c.id) }) }, label = { Text(c.title) }, modifier = Modifier.weight(1f))
        } } }
        item { SectionTitle("Разрешение записи") }
        item { ChoiceRow(listOf(640 to "640×400", 960 to "960×600", 1280 to "1280×800"), config.width, enabled) { width ->
            config = config.copy(width = width, height = when (width) { 640 -> 400; 1280 -> 800; else -> 600 })
        } }
        item { SectionTitle("Частота записи") }
        item { ChoiceRow(listOf(15 to "15 FPS", 20 to "20 FPS", 25 to "25 FPS"), config.fps, enabled) { config = config.copy(fps = it) } }
        item { SectionTitle("Битрейт H.264 на камеру") }
        item { ChoiceRow(listOf(2 to "2 Мбит/с", 3 to "3 Мбит/с", 4 to "4 Мбит/с", 6 to "6 Мбит/с"), config.bitrateMbps, enabled) { config = config.copy(bitrateMbps = it) } }
        item { SectionTitle("Длительность сегмента") }
        item { ChoiceRow(listOf(1 to "1 мин", 2 to "2 мин", 3 to "3 мин", 5 to "5 мин"), config.segmentMinutes, enabled) { config = config.copy(segmentMinutes = it) } }
        item { SectionTitle("Лимит архива: ${config.storageLimitGb} ГБ из доступных $maxArchiveGb ГБ")
            Slider(config.storageLimitGb.toFloat(), { config = config.copy(storageLimitGb = it.toInt()) },
                valueRange = minArchiveGb.toFloat()..maxArchiveGb.coerceAtLeast(minArchiveGb).toFloat(), enabled = enabled)
            Text("Память устройства: занято ${formatBytes(storage.totalBytes - storage.freeBytes)} из ${formatBytes(storage.totalBytes)} · свободно ${formatBytes(storage.freeBytes)}", fontSize = 13.sp)
            Text("Записи gCam: ${formatBytes(storage.recordingBytes)} · резерв 5 ГБ сохраняется автоматически", fontSize = 13.sp)
            Text("Ориентировочно ${"%.1f".format(Locale.US, hours)} ч записи", fontSize = 13.sp)
        }
        item { SectionTitle("Размер интерфейса: ${(uiScale * 100).toInt()}%")
            Slider(uiScale, onUiScaleChange, valueRange = 1.3f..1.8f, steps = 9)
        }
        item { Button(onClick = {
            if (state.recording) DashcamService.stop(context) else {
                preparing = true
                RecorderRepository.update(RecorderState(recording = true, status = "Подготовка записи…"))
                scope.launch {
                    delay(800)
                    RecorderSettings.save(context, config)
                    DashcamService.start(context)
                    preparing = false
                }
            }
        }, enabled = state.recording || config.cameraIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.recording) "Остановить запись" else "Начать запись")
        } }
    }
}

@Composable private fun MultiCameraGrid() {
    val context = LocalContext.current
    val controller = remember { MultiCameraPreviewController(context) }
    DisposableEffect(controller) { onDispose { controller.close() } }
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
                            while (true) {
                                delay(500)
                                status = controller.status(input.id)
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
                Text(status, color = Color.White, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)
@Composable private fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { (v, title) -> FilterChip(selected = selected == v, enabled = enabled, onClick = { onSelect(v) }, label = { Text(title) }, modifier = Modifier.weight(1f)) } }
}

@Composable private fun RecordingsScreen() {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<RecordingFile?>(null) }
    val recordings = remember(revision) { RecordingStore.list(context) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) { RecordingStore.migrateLegacy(context) }
        revision++
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Movies/gCam · ${recordings.size} файлов · ${formatBytes(recordings.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.titleMedium) }
        if (recordings.isEmpty()) item { Text("Записей пока нет") }
        items(recordings, key = RecordingFile::uri) { r -> Card { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("${recorderCameraNames[r.inputId] ?: "Камера ${r.inputId}"}${if (r.protected) " · защищено" else ""}"); Text("${DateFormat.getDateTimeInstance().format(Date(r.modifiedAt))} · ${formatBytes(r.sizeBytes)}", fontSize = 12.sp) }
            TextButton(onClick = { playing = r }) { Text("Смотреть") }
            TextButton(onClick = { RecordingStore.setProtected(context, r, !r.protected); revision++ }) { Text(if (r.protected) "Снять защиту" else "Защитить") }
            TextButton(onClick = { RecordingStore.delete(context, r); revision++ }) { Text("Удалить") }
        } } }
    }
    playing?.let { VideoPlayer(it) { playing = null } }
}

@Composable private fun VideoPlayer(recording: RecordingFile, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val video = remember(recording.uri) { VideoView(context) }
        DisposableEffect(video) { onDispose { video.stopPlayback() } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = {
                video.apply {
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(Uri.parse(recording.uri))
                    setOnPreparedListener { player -> player.isLooping = false; start() }
                }
            }, modifier = Modifier.fillMaxSize())
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Закрыть") }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f МБ".format(Locale.US, bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} КБ"
}
