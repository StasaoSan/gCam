package com.stasao.gcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stasao.gcam.ui.theme.GCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GCamTheme { GCamApp() } }
    }
}

fun checkCameraPerm(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GCamApp() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPerm by remember { mutableStateOf(checkCameraPerm(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPerm = granted }

    // Re-check permission every time app comes back to foreground (e.g. after Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPerm = checkCameraPerm(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val diagResults = remember { mutableStateListOf<CheckResult>() }
    var diagRunning by remember { mutableStateOf(false) }
    var diagDone by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun runDiag() {
        scope.launch {
            diagRunning = true
            diagResults.clear()
            diagDone = false
            DiagnosticsEngine(context).runAll { diagResults.add(it) }
            diagRunning = false
            diagDone = true
        }
    }

    // Request permission on first launch
    LaunchedEffect(Unit) {
        if (!hasPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }
    // Run diagnostics automatically when permission becomes available
    LaunchedEffect(hasPerm) {
        if (hasPerm && !diagDone && !diagRunning) runDiag()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("gCam Diagnostics", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    if (diagDone) {
                        TextButton(onClick = {
                            val text = diagResults.joinToString("\n\n") {
                                "[${it.status.name}] ${it.title}\n${it.detail}"
                            }
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("gCam Diag", text))
                            Toast.makeText(context, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
                        }) { Text("Копировать") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Диагностика") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Камера") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    text = { Text("Монитор") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                    text = { Text("Инструменты") })
            }
            when (selectedTab) {
                0 -> DiagnosticsTab(
                    results = diagResults,
                    isRunning = diagRunning,
                    hasPerm = hasPerm,
                    onRerun = { runDiag() },
                    onRequestPerm = { permLauncher.launch(Manifest.permission.CAMERA) }
                )
                1 -> CameraTab(hasPerm)
                2 -> MonitorTab()
                3 -> ToolsTab()
            }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val isPermanentlyDenied = activity?.let {
        !it.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) &&
        !checkCameraPerm(context)
    } ?: false

    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("Нужно разрешение CAMERA",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFB71C1C))

            Text(
                "Без разрешения camera диагностика будет неполной.\n" +
                "Camera2, Camera1 и GID 1006 проверки недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isPermanentlyDenied) {
                Text(
                    "Разрешение было отклонено навсегда.\nОткрой настройки и выдай вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE65100)
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                ) { Text("Открыть настройки приложения") }
            } else {
                Button(onClick = onRequest) { Text("Выдать разрешение CAMERA") }
            }

            HorizontalDivider()
            Text("Или через ADB:", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = Color(0xFFE3F2FD), shape = MaterialTheme.shapes.small) {
                Text(
                    "adb shell pm grant ${context.packageName}\n  android.permission.CAMERA",
                    Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = Color(0xFF0D47A1)
                )
            }
        }
    }
}

@Composable
fun DiagnosticsTab(
    results: List<CheckResult>,
    isRunning: Boolean,
    hasPerm: Boolean,
    onRerun: () -> Unit,
    onRequestPerm: () -> Unit
) {
    if (!hasPerm && results.isEmpty()) {
        PermissionScreen(onRequest = onRequestPerm)
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (!hasPerm) {
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Text(
                    "Нет разрешения CAMERA — часть проверок недоступна",
                    Modifier.padding(12.dp), color = Color(0xFFB71C1C), fontSize = 12.sp
                )
            }
        }
        if (isRunning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Выполняется диагностика...", Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
        if (results.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${results.size} проверок", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!isRunning) {
                    TextButton(onClick = onRerun) { Text("Повторить") }
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(results, key = { it.title }) { DiagCard(it) }
        }
    }
}

@Composable
fun DiagCard(result: CheckResult) {
    val (bg, fg, label) = when (result.status) {
        CheckStatus.OK   -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), "OK")
        CheckStatus.WARN -> Triple(Color(0xFFFFF8E1), Color(0xFFE65100), "WARN")
        CheckStatus.FAIL -> Triple(Color(0xFFFFEBEE), Color(0xFFB71C1C), "FAIL")
        CheckStatus.INFO -> Triple(Color(0xFFE3F2FD), Color(0xFF0D47A1), "INFO")
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(result.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg)
                Text(
                    label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
                    modifier = Modifier
                        .background(fg.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(result.detail, fontSize = 11.sp, color = fg.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        }
    }
}

@Composable
fun CameraTab(hasPerm: Boolean) {
    if (!hasPerm) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("Нет разрешения CAMERA", color = Color(0xFFB71C1C))
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController(context) }

    var statusText by remember { mutableStateOf("Ожидание Surface...") }
    var methodText by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    val surfaceTextureState = remember { mutableStateOf<SurfaceTexture?>(null) }
    val surfaceSize = remember { mutableStateOf(Pair(1280, 720)) }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    LaunchedEffect(surfaceTextureState.value, retryKey) {
        val st = surfaceTextureState.value ?: return@LaunchedEffect
        val (w, h) = surfaceSize.value

        controller.release()
        isSuccess = false
        methodText = ""

        // Attempt Camera2
        statusText = "Пробуем Camera2..."
        val r2 = withContext(Dispatchers.IO) { controller.tryCamera2(st, w, h) }
        if (r2 is CameraOpenResult.Success) {
            statusText = "Работает!"
            methodText = r2.method
            isSuccess = true
            return@LaunchedEffect
        }

        // Attempt Camera1
        statusText = "Camera2 не удалась (${(r2 as CameraOpenResult.Failure).reason})\nПробуем Camera1..."
        val r1 = withContext(Dispatchers.IO) { controller.tryCamera1(st, w, h) }
        if (r1 is CameraOpenResult.Success) {
            statusText = "Работает!"
            methodText = r1.method
            isSuccess = true
            return@LaunchedEffect
        }

        statusText = "Обе попытки не удались:\n" +
            "Camera2: ${r2.reason}\n" +
            "Camera1: ${(r1 as CameraOpenResult.Failure).reason}"
        methodText = ""
        isSuccess = false
    }

    Column(Modifier.fillMaxSize()) {
        // Status bar
        Surface(
            Modifier.fillMaxWidth(),
            color = when {
                isSuccess -> Color(0xFF1B5E20)
                statusText.contains("не удал") -> Color(0xFFB71C1C)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val textColor = if (isSuccess || statusText.contains("не удал"))
                    Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                Text(statusText, color = textColor, fontSize = 12.sp, lineHeight = 16.sp)
                if (methodText.isNotEmpty()) {
                    Text("Метод: $methodText", color = Color.White,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // TextureView for camera preview
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            surfaceSize.value = Pair(width, height)
                            surfaceTextureState.value = surface
                        }
                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            surfaceSize.value = Pair(width, height)
                        }
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            surfaceTextureState.value = null
                            controller.release()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        // Retry button, shown only on failure
        if (!isSuccess && !statusText.startsWith("Ожидание") && !statusText.startsWith("Пробуем")) {
            Button(
                onClick = { retryKey++ },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Попробовать ещё раз")
            }
        }
    }
}

@Composable
fun ToolsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copyStatus by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }

    val autoPkgs = remember {
        try {
            context.packageManager.getInstalledPackages(0).filter { pkg ->
                listOf("parking", "surround", "360", "avm", "ecarx", "evs", "adas")
                    .any { pkg.packageName.contains(it, true) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun extractApk(apkPath: String, pkgName: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            isWorking = true
            copyStatus = "Извлекаю $pkgName..."

            val fileName = "${pkgName.substringAfterLast(".").take(20)}.apk"
            // App's own external dir — always writable, no permissions needed
            val destFile = java.io.File(context.getExternalFilesDir(null), fileName)

            copyStatus = try {
                java.io.File(apkPath).copyTo(destFile, overwrite = true)
                "✓ Извлечено:\n${destFile.absolutePath}\n\nADB pull:\nadb pull \"${destFile.absolutePath}\""
            } catch (e: Exception) {
                // Shell cp to the same target dir (shell inherits app uid, same write access)
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("cp", apkPath, destFile.absolutePath))
                    val ok = proc.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    val err = proc.errorStream.bufferedReader().readText().trim()
                    if (ok && proc.exitValue() == 0 && destFile.exists()) {
                        "✓ Извлечено (cp):\n${destFile.absolutePath}\n\nADB pull:\nadb pull \"${destFile.absolutePath}\""
                    } else {
                        "Ошибка: $err\n\nВручную на устройстве:\ncp \"$apkPath\" /sdcard/Download/$fileName"
                    }
                } catch (e2: Exception) {
                    "Ошибка: ${e2.message}\n\nВручную:\ncp \"$apkPath\" /sdcard/Download/$fileName"
                }
            }
            isWorking = false
        }
    }

    fun extractAll() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            isWorking = true
            val results = mutableListOf<String>()
            for (pkg in autoPkgs) {
                val apkPath = try {
                    context.packageManager.getPackageInfo(pkg.packageName, 0).applicationInfo?.sourceDir
                } catch (_: Exception) { null } ?: continue
                val fileName = "${pkg.packageName.substringAfterLast(".").take(20)}.apk"
                val destFile = java.io.File(context.getExternalFilesDir(null), fileName)
                try {
                    java.io.File(apkPath).copyTo(destFile, overwrite = true)
                    results.add("✓ ${pkg.packageName.substringAfterLast(".")}")
                } catch (_: Exception) {
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("cp", apkPath, destFile.absolutePath))
                        proc.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (destFile.exists()) results.add("✓ ${pkg.packageName.substringAfterLast(".")}")
                        else results.add("✗ ${pkg.packageName.substringAfterLast(".")}")
                    } catch (_: Exception) {
                        results.add("✗ ${pkg.packageName.substringAfterLast(".")}")
                    }
                }
            }
            val dir = context.getExternalFilesDir(null)?.absolutePath ?: "?"
            copyStatus = results.joinToString("\n") + "\n\nADB pull всё:\nadb pull \"$dir\""
            isWorking = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Инструменты", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "Извлекает APK системных камера-приложений в папку приложения.\nПотом: adb pull или USB-файлменеджер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (autoPkgs.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Text("Automotive APK (parking/surround/avm/ecarx) не найдены",
                        Modifier.padding(12.dp), color = Color(0xFFB71C1C), fontSize = 12.sp)
                }
            }
        } else {
            item {
                Button(
                    onClick = { extractAll() },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Text(if (isWorking) "Работаю..." else "Извлечь все APK (${autoPkgs.size})")
                }
            }
        }

        items(autoPkgs) { pkg ->
            val apkPath = try {
                context.packageManager.getPackageInfo(pkg.packageName, 0).applicationInfo?.sourceDir
            } catch (_: Exception) { null }
            val isSystem = pkg.applicationInfo?.flags
                ?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pkg.packageName, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = Color(0xFF1B5E20))
                    Text("[${if (isSystem) "system" else "user"}]",
                        fontSize = 10.sp, color = Color(0xFF2E7D32))
                    Text(apkPath ?: "APK не найден",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = Color(0xFF2E7D32))

                    if (apkPath != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { extractApk(apkPath, pkg.packageName) },
                                enabled = !isWorking,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Извлечь", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    // cp command as the user runs it manually on device
                                    val fileName = "${pkg.packageName.substringAfterLast(".").take(20)}.apk"
                                    val cmd = "cp \"$apkPath\" /sdcard/Download/$fileName"
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("cp", cmd))
                                    Toast.makeText(context, "cp команда скопирована", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("cp команда", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        if (copyStatus.isNotEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth(),
                    color = if (copyStatus.startsWith("✓")) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    shape = MaterialTheme.shapes.small) {
                    Text(
                        copyStatus, Modifier.padding(12.dp),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = if (copyStatus.startsWith("✓")) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Jadx (деком­пилятор APK):", style = MaterialTheme.typography.labelMedium)
            Surface(Modifier.fillMaxWidth(), color = Color(0xFFE3F2FD), shape = MaterialTheme.shapes.small) {
                Text(
                    "# После adb pull:\njadx -d ecarx_src ecarx.apk\ngrep -r \"IQcarCamera\\|qcarcam\\|HwBinder\" ecarx_src/",
                    Modifier.padding(10.dp),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFF0D47A1), lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun MonitorTab() {
    val context = LocalContext.current
    val logLines by MonitorService.lines.collectAsState()
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new lines arrive
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    val logFilePath = remember {
        MonitorService.logFile(context).absolutePath
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // Control row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (!running) {
                        context.startForegroundService(Intent(context, MonitorService::class.java))
                        running = true
                    } else {
                        context.stopService(Intent(context, MonitorService::class.java))
                        running = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFB71C1C) else Color(0xFF1B5E20)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (running) "Остановить" else "Запустить монитор")
            }

            if (logLines.isNotEmpty()) {
                TextButton(onClick = {
                    val text = logLines.joinToString("\n")
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("gcam_log", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }) { Text("Копировать") }
            }
        }

        // ADB pull hint
        Surface(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = Color(0xFFE3F2FD),
            shape = MaterialTheme.shapes.small
        ) {
            Column(Modifier.padding(8.dp)) {
                Text("Файл лога (ADB):", fontSize = 10.sp, color = Color(0xFF0D47A1))
                Text(
                    "adb pull $logFilePath",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFF0D47A1)
                )
            }
        }

        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(Modifier.weight(1f))
                Text("  ${logLines.size} записей", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
        }

        if (logLines.isEmpty() && !running) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Нажми «Запустить монитор», сверни приложение,\nпоставь машину на заднюю передачу,\nпотом вернись и смотри лог.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(logLines) { line ->
                    val color = when {
                        line.contains("ИЗМЕНЕНИЕ") && line.contains("DIR(") &&
                            !line.contains("пусто") && !line.contains("нет доступа") -> Color(0xFF1B5E20)
                        line.contains("КАМЕРЫ:") -> Color(0xFF1B5E20)
                        line.contains("ИЗМЕНЕНИЕ") -> Color(0xFFE65100)
                        line.contains("===") -> Color(0xFF0D47A1)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
