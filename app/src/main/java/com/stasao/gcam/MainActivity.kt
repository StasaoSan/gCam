package com.stasao.gcam

import android.graphics.SurfaceTexture
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.stasao.gcam.ui.theme.GCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class CameraInput(val id: Int, val title: String)
private val cameraInputs = listOf(CameraInput(0, "Левая"), CameraInput(1, "Правая"), CameraInput(2, "Перед"), CameraInput(3, "Зад"))

class MainActivity : ComponentActivity() {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GCamApp() {
    var tab by remember { mutableIntStateOf(0) }
    val recorder by RecorderRepository.state.collectAsState()
    Scaffold(topBar = { Column {
        TopAppBar(title = { Text("Камеры 360°") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
        PrimaryTabRow(selectedTabIndex = tab) { listOf("Камеры", "Регистратор", "Записи").forEachIndexed { i, title ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
        } }
    } }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when (tab) {
            0 -> if (recorder.recording) RecordingPlaceholder(recorder) { tab = 1 } else CameraScreen()
            1 -> RecorderScreen(recorder)
            else -> RecordingsScreen()
        }
    } }
}

@Composable private fun RecordingPlaceholder(state: RecorderState, openRecorder: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Идёт многокамерная запись", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(8.dp)); Text(state.status)
        Spacer(Modifier.height(16.dp)); Button(onClick = openRecorder) { Text("Открыть регистратор") }
    }
}

@Composable private fun CameraScreen() {
    val scope = rememberCoroutineScope(); val controller = remember { QCarCamController() }
    var selectedInput by remember { mutableIntStateOf(0) }; var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var state by remember { mutableStateOf(QCarCamState("Подготовка видеопотока…", false, 0, 0, 0, 0.0)) }
    LaunchedEffect(Unit) { controller.states().collect { state = it } }
    DisposableEffect(Unit) { onDispose { controller.stop(); previewSurface?.release() } }
    Column(Modifier.fillMaxSize()) {
        CameraStatus(state)
        AndroidView(factory = { context -> TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    previewSurface?.release(); previewSurface = Surface(texture).also { s -> scope.launch(Dispatchers.IO) { controller.start(s, selectedInput) } }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    scope.launch(Dispatchers.IO) { controller.stop() }; previewSurface?.release(); previewSurface = null; return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        } }, modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cameraInputs.forEach { input -> FilterChip(selected = selectedInput == input.id, onClick = {
                selectedInput = input.id; previewSurface?.takeIf(Surface::isValid)?.let { s -> scope.launch(Dispatchers.IO) { controller.start(s, input.id) } }
            }, label = { Text(input.title) }, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable private fun CameraStatus(state: QCarCamState) {
    val error = listOf("ошибка", "недоступен", "не удалось").any { state.status.contains(it, true) }
    val background = when { state.running -> Color(0xFF1B5E20); error -> Color(0xFF8E2020); else -> MaterialTheme.colorScheme.surfaceVariant }
    val foreground = if (state.running || error) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth(), color = background) { Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(state.status, color = foreground, fontSize = 12.sp)
        if (state.frames > 0) Text("%.1f fps · %d".format(Locale.US, state.fps, state.frames), color = foreground, fontSize = 11.sp)
    } }
}

@Composable private fun RecorderScreen(state: RecorderState) {
    val context = LocalContext.current; var config by remember { mutableStateOf(RecorderSettings.load(context)) }; val enabled = !state.recording
    LaunchedEffect(state.recording) { if (!state.recording) RecorderRepository.update(state.copy(usedBytes = RecordingStore.usedBytes(context))) }
    val hours = config.storageLimitGb * 8192.0 / (config.bitrateMbps * config.cameraIds.size.coerceAtLeast(1) * 3600.0)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Surface(color = if (state.recording) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(state.status, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface)
                state.cameras.values.sortedBy { it.inputId }.forEach { c -> Text("${recorderCameraNames[c.inputId]}: ${"%.1f".format(Locale.US, c.fps)} fps${c.error?.let { " · $it" } ?: ""}", fontSize = 12.sp, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface) }
            }
        } }
        item { SectionTitle("Камеры для записи") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { cameraInputs.forEach { c ->
            FilterChip(enabled = enabled, selected = c.id in config.cameraIds, onClick = { config = config.copy(cameraIds = config.cameraIds.toMutableSet().apply { if (!add(c.id)) remove(c.id) }) }, label = { Text(c.title) }, modifier = Modifier.weight(1f))
        } } }
        item { SectionTitle("Качество H.264 на камеру") }
        item { ChoiceRow(listOf(3 to "Эконом", 4 to "Стандарт", 6 to "Высокое"), config.bitrateMbps, enabled) { config = config.copy(bitrateMbps = it) } }
        item { SectionTitle("Длительность сегмента") }
        item { ChoiceRow(listOf(1 to "1 мин", 2 to "2 мин", 3 to "3 мин", 5 to "5 мин"), config.segmentMinutes, enabled) { config = config.copy(segmentMinutes = it) } }
        item { SectionTitle("Лимит архива: ${config.storageLimitGb} ГБ")
            Slider(config.storageLimitGb.toFloat(), { config = config.copy(storageLimitGb = (it / 5).toInt() * 5) }, valueRange = 5f..100f, steps = 18, enabled = enabled)
            Text("Резерв свободного места: ${config.reserveGb} ГБ", fontSize = 13.sp)
            Slider(config.reserveGb.toFloat(), { config = config.copy(reserveGb = it.toInt()) }, valueRange = 2f..20f, steps = 17, enabled = enabled)
            Text("Ориентировочно ${"%.1f".format(Locale.US, hours)} ч записи · занято ${formatBytes(state.usedBytes)}", fontSize = 13.sp)
        }
        item { Button(onClick = { if (state.recording) DashcamService.stop(context) else { RecorderSettings.save(context, config); DashcamService.start(context) } }, enabled = state.recording || config.cameraIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.recording) "Остановить запись" else "Начать запись")
        } }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)
@Composable private fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { (v, title) -> FilterChip(selected = selected == v, enabled = enabled, onClick = { onSelect(v) }, label = { Text(title) }, modifier = Modifier.weight(1f)) } }
}

@Composable private fun RecordingsScreen() {
    val context = LocalContext.current; var revision by remember { mutableIntStateOf(0) }; val recordings = remember(revision) { RecordingStore.list(context) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("${recordings.size} файлов · ${formatBytes(recordings.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.titleMedium) }
        if (recordings.isEmpty()) item { Text("Записей пока нет") }
        items(recordings, key = RecordingFile::path) { r -> Card { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("${recorderCameraNames[r.inputId] ?: "Камера ${r.inputId}"}${if (r.protected) " · защищено" else ""}"); Text("${DateFormat.getDateTimeInstance().format(Date(r.modifiedAt))} · ${formatBytes(r.sizeBytes)}", fontSize = 12.sp) }
            TextButton(onClick = { RecordingStore.setProtected(context, r, !r.protected); revision++ }) { Text(if (r.protected) "Снять защиту" else "Защитить") }
            TextButton(onClick = { RecordingStore.delete(r); revision++ }) { Text("Удалить") }
        } } }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f МБ".format(Locale.US, bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} КБ"
}
