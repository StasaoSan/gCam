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
import android.view.SurfaceHolder
import android.view.SurfaceView
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
    var exportPath by remember { mutableStateOf("") }

    fun runDiag() {
        scope.launch {
            diagRunning = true
            diagResults.clear()
            diagDone = false
            exportPath = ""
            DiagnosticsEngine(context).runAll { diagResults.add(it) }
            diagRunning = false
            diagDone = true
        }
    }

    fun exportDiagLog() {
        scope.launch(Dispatchers.IO) {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US)
                .format(java.util.Date())
            val fileName = "gcam_diag_$ts.txt"
            val text = buildString {
                appendLine("=== gCam Diagnostics ===")
                appendLine("Device : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Date   : $ts")
                appendLine("=".repeat(50))
                appendLine()
                diagResults.forEach { r ->
                    appendLine("[${r.status.name}] ${r.title}")
                    appendLine(r.detail.trimEnd())
                    appendLine()
                }
            }

            // Сохраняем через MediaStore.Downloads → /sdcard/Download/ (Android 10+, без доп. разрешений)
            var savedPath = ""
            try {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    savedPath = "/sdcard/Download/$fileName"
                }
            } catch (_: Exception) {}

            // Fallback: приватная папка приложения (всегда работает)
            if (savedPath.isEmpty()) {
                val file = java.io.File(context.getExternalFilesDir(null), fileName)
                file.writeText(text)
                savedPath = file.absolutePath
            }

            val finalPath = savedPath
            withContext(Dispatchers.Main) {
                exportPath = finalPath
                Toast.makeText(context, "Сохранено: $fileName", Toast.LENGTH_SHORT).show()
            }
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
                        TextButton(onClick = { exportDiagLog() }) {
                            Text("Экспорт .txt")
                        }
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
                    exportPath = exportPath,
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
    exportPath: String,
    onRerun: () -> Unit,
    onRequestPerm: () -> Unit
) {
    val context = LocalContext.current
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
        // Показ пути экспортированного файла + adb pull
        if (exportPath.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val inDownload = exportPath.startsWith("/sdcard/Download")
                    Text(if (inDownload) "Сохранено в /sdcard/Download/" else "Сохранено (fallback):",
                        fontSize = 11.sp, color = Color(0xFF1B5E20), fontWeight = FontWeight.Bold)
                    Text(exportPath, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = Color(0xFF2E7D32))
                    if (!inDownload) {
                        Text("adb pull \"$exportPath\"",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF1565C0))
                    }
                    Text(
                        if (inDownload) "Доступен в файловом менеджере → Download"
                        else "Crash recovery: adb pull /sdcard/Android/data/com.stasao.gcam/files/gcam_last_run.txt",
                        fontSize = 9.sp, color = Color(0xFF388E3C)
                    )
                    TextButton(
                        onClick = {
                            val cmd = if (inDownload) "adb pull \"$exportPath\""
                                      else "adb pull \"$exportPath\""
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Скопировать путь", fontSize = 11.sp) }
                }
            }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController(context) }

    var statusText by remember { mutableStateOf("Ожидание Surface...") }
    var methodText by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    val surfaceHolderState = remember { mutableStateOf<SurfaceHolder?>(null) }
    val surfaceSize = remember { mutableStateOf(Pair(0, 0)) }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    LaunchedEffect(surfaceHolderState.value, surfaceSize.value, retryKey) {
        val holder = surfaceHolderState.value ?: return@LaunchedEffect
        val (w, h) = surfaceSize.value
        if (w == 0 || h == 0) return@LaunchedEffect

        controller.release()
        isSuccess = false
        methodText = ""

        // Попытка 1: Camera2
        statusText = "Пробуем Camera2..."
        val r2 = withContext(Dispatchers.IO) { controller.tryCamera2(holder, w, h) }
        if (r2 is CameraOpenResult.Success) {
            statusText = "Работает!"; methodText = r2.method; isSuccess = true
            return@LaunchedEffect
        }

        // Попытка 2: Camera1
        statusText = "Camera2: ${(r2 as CameraOpenResult.Failure).reason}\nПробуем Camera1..."
        val r1 = withContext(Dispatchers.IO) { controller.tryCamera1(holder, w, h) }
        if (r1 is CameraOpenResult.Success) {
            statusText = "Работает!"; methodText = r1.method; isSuccess = true
            return@LaunchedEffect
        }

        // Попытка 3: ECarX EVSImp
        statusText = "Camera1: ${(r1 as CameraOpenResult.Failure).reason}\nПробуем ECarX EVS..."
        val rE = withContext(Dispatchers.IO) { controller.tryEvsCamera(holder) }
        if (rE is CameraOpenResult.Success) {
            statusText = "Работает!"; methodText = rE.method; isSuccess = true
            return@LaunchedEffect
        }

        statusText = "Все методы не удались:\n" +
            "Camera2: ${r2.reason}\n" +
            "Camera1: ${r1.reason}\n" +
            "EVSImp:  ${(rE as CameraOpenResult.Failure).reason}"
        isSuccess = false
    }

    Column(Modifier.fillMaxSize()) {
        // Статус-бар
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

        // SurfaceView — поддерживает и Camera1 и EVSImp (оба ждут SurfaceHolder)
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            surfaceHolderState.value = h
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surfaceSize.value = Pair(width, height)
                            surfaceHolderState.value = h
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            surfaceHolderState.value = null
                            controller.release()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        if (!isSuccess && !statusText.startsWith("Ожидание") && !statusText.startsWith("Пробуем")) {
            Button(
                onClick = { retryKey++ },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text("Попробовать ещё раз") }
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

    var avmLog by remember { mutableStateOf("") }

    // ── Camera preview (MediaProjection + AVM) ──
    var camTextureST by remember { mutableStateOf<SurfaceTexture?>(null) }
    var camProjection by remember { mutableStateOf<android.media.projection.MediaProjection?>(null) }
    var camVirtualDisplay by remember { mutableStateOf<android.hardware.display.VirtualDisplay?>(null) }
    var camSurf by remember { mutableStateOf<android.view.Surface?>(null) }
    var isCamCapturing by remember { mutableStateOf(false) }
    var camStatus by remember { mutableStateOf("") }
    var camMpCode by remember { mutableStateOf(0) }
    var camMpData by remember { mutableStateOf<Intent?>(null) }

    val camMpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            camMpCode = result.resultCode
            camMpData = result.data
        } else {
            camStatus = "Разрешение MediaProjection не выдано"
        }
    }

    fun stopCamCapture() {
        scope.launch(Dispatchers.IO) {
            try {
                val cr = Class.forName("ecarx.fw.api.PasFunc.PasFunc\$\$Creator").newInstance()
                val pf = cr.javaClass.getMethod("create", Context::class.java).invoke(cr, context)!!
                pf.javaClass.getMethod("startOrStopAvm", Int::class.javaPrimitiveType).invoke(pf, 0)
            } catch (_: Exception) {}
        }
        camVirtualDisplay?.release(); camVirtualDisplay = null
        camProjection?.stop();        camProjection = null
        camSurf?.release();           camSurf = null
        isCamCapturing = false
        camMpCode = 0; camMpData = null
        camStatus = "Захват остановлен"
    }

    fun callPasFunc(action: Int) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            avmLog = try {
                val creator = Class.forName("ecarx.fw.api.PasFunc.PasFunc\$\$Creator").newInstance()
                val pasFunc = creator.javaClass.getMethod("create", Context::class.java)
                    .invoke(creator, context)!!
                when (action) {
                    1, 0 -> {
                        pasFunc.javaClass
                            .getMethod("startOrStopAvm", Int::class.javaPrimitiveType)
                            .invoke(pasFunc, action)
                        "PasFunc.startOrStopAvm($action) → OK"
                    }
                    -1 -> {
                        val state = pasFunc.javaClass.getMethod("getAVMState").invoke(pasFunc)
                        val pdcState = pasFunc.javaClass.getMethod("getPDCState").invoke(pasFunc)
                        val apaState = pasFunc.javaClass.getMethod("getAPAState").invoke(pasFunc)
                        "AVM=$state  PDC=$pdcState  APA=$apaState"
                    }
                    else -> "?"
                }
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
        }
    }

    // When both MP permission AND TextureView SurfaceTexture are ready → create VirtualDisplay
    LaunchedEffect(camMpCode, camTextureST) {
        if (camMpCode != 0 && camMpData != null && camTextureST != null && !isCamCapturing) {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            val mp = mgr.getMediaProjection(camMpCode, camMpData!!)
                ?: run { camStatus = "MediaProjection: null — попробуйте снова"; return@LaunchedEffect }
            camProjection = mp
            val dm = context.resources.displayMetrics
            val st = camTextureST ?: return@LaunchedEffect
            st.setDefaultBufferSize(dm.widthPixels, dm.heightPixels)
            val surf = android.view.Surface(st)
            camSurf = surf
            val vd = mp.createVirtualDisplay(
                "gcam_preview", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                4 /* VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR */, surf, null, null
            )
            camVirtualDisplay = vd
            isCamCapturing = true
            camStatus = "VirtualDisplay активен — запускаю AVM..."
            withContext(Dispatchers.IO) {
                try {
                    val cr = Class.forName("ecarx.fw.api.PasFunc.PasFunc\$\$Creator").newInstance()
                    val pf = cr.javaClass.getMethod("create", Context::class.java).invoke(cr, context)!!
                    pf.javaClass.getMethod("startOrStopAvm", Int::class.javaPrimitiveType).invoke(pf, 1)
                    withContext(Dispatchers.Main) { camStatus = "AVM запущен — изображение в TextureView ниже" }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { camStatus = "AVM error: ${e.message?.take(80)}" }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { camVirtualDisplay?.release(); camProjection?.stop(); camSurf?.release() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("AVM Управление", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp))
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PasFunc (прямой вызов через системный ClassLoader)",
                        style = MaterialTheme.typography.labelMedium, color = Color(0xFF1A237E))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { callPasFunc(1) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                            modifier = Modifier.weight(1f)
                        ) { Text("▶ AVM Старт") }
                        Button(
                            onClick = { callPasFunc(0) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                            modifier = Modifier.weight(1f)
                        ) { Text("■ AVM Стоп") }
                        OutlinedButton(
                            onClick = { callPasFunc(-1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Статус") }
                    }

                    Button(
                        onClick = {
                            try {
                                val intent = android.content.Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.ecarx.parking",
                                        "com.ecarx.parking.MainAvmActivity"
                                    )
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                avmLog = "Intent → com.ecarx.parking запущен"
                            } catch (e: Exception) {
                                avmLog = "Intent failed: ${e.message}"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Запустить com.ecarx.parking (Intent)") }

                    if (avmLog.isNotEmpty()) {
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = if (avmLog.contains("OK") || avmLog.contains("запущен"))
                                Color(0xFFE8F5E9) else Color(0xFFFFF8E1),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(avmLog, Modifier.padding(8.dp),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = if (avmLog.contains("OK") || avmLog.contains("запущен"))
                                    Color(0xFF1B5E20) else Color(0xFFE65100),
                                lineHeight = 15.sp)
                        }
                    }
                }
            }
        }

        // ── Camera Preview card ──
        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Камера (MediaProjection + AVM)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE1F5FE))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "1. Нажмите «Запустить» — появится диалог «Разрешить захват экрана?»\n" +
                        "2. Разрешите → AVM запустится автоматически (камера в оверлее)\n" +
                        "3. TextureView ниже захватывает то же изображение через VirtualDisplay\n" +
                        "4. Нажмите «Стоп» (или AVM Стоп выше) чтобы закончить",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF01579B), lineHeight = 16.sp, fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as android.media.projection.MediaProjectionManager
                                camStatus = "Ожидание разрешения MediaProjection..."
                                camMpLauncher.launch(mgr.createScreenCaptureIntent())
                            },
                            enabled = !isCamCapturing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD)),
                            modifier = Modifier.weight(1f)
                        ) { Text("▶ Запустить", fontSize = 12.sp) }
                        Button(
                            onClick = { stopCamCapture() },
                            enabled = isCamCapturing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                            modifier = Modifier.weight(1f)
                        ) { Text("■ Стоп", fontSize = 12.sp) }
                    }
                    if (camStatus.isNotEmpty()) {
                        Text(camStatus, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (isCamCapturing) Color(0xFF01579B) else Color(0xFFE65100))
                    }
                    // TextureView — receives VirtualDisplay output (mirrored main display)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                            camTextureST = st
                                        }
                                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                            camTextureST = null
                                            return true
                                        }
                                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (!isCamCapturing) {
                            Text("Нет сигнала", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Извлечение APK", style = MaterialTheme.typography.titleMedium,
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
