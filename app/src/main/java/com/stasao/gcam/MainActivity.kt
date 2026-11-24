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
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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

private data class CameraInput(val id: Int, @param:androidx.annotation.StringRes val title: Int)
private data class CameraDiagnostics(val zeroCopy: Boolean, val fps: Double, val maxGapMs: Double)
private data class LinkHealth(val receivedAt: Long = 0, val enabled: Boolean = false,
                              val connected: Boolean = false, val video: Boolean = false,
                              val error: String? = null)
private val cameraInputs = listOf(CameraInput(0, R.string.gcam_left), CameraInput(1, R.string.gcam_right), CameraInput(2, R.string.gcam_front), CameraInput(3, R.string.gcam_rear))
private val LocalGCamEnglish = compositionLocalOf { false }

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase:Context) {
        super.attachBaseContext(RecorderHost.wrapContext(newBase))
    }
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
        super.onCreate(savedInstanceState)
        GCamLanguage.migrate(this)
        if (RecorderHost.embedded(this)) {
            val chrome = 0xFF080F19.toInt()
            enableEdgeToEdge(SystemBarStyle.dark(chrome), SystemBarStyle.dark(chrome))
        } else enableEdgeToEdge()
        setContent {
            GCamTheme {
                Column(Modifier.fillMaxSize()) {
                    RecorderHost.navigation?.let { navigation ->
                        AndroidView(
                            factory = { navigation(this@MainActivity) },
                            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                        )
                    }
                    Box(Modifier.weight(1f)) { GCamApp() }
                }
            }
        }
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
        if (RecorderHost.embedded(this)) {
            requestEmbeddedPermissions(initial = true)
            return
        }
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

    internal fun requestEmbeddedPermissions(initial: Boolean = false) {
        val config=RecorderSettings.load(this)
        val missing=if(initial) EmbeddedRecorderAccess.initialRuntimePermissions(this,config)
            else EmbeddedRecorderAccess.runtimePermissions(this,config)
        if(missing.isNotEmpty()) {
            EmbeddedRecorderAccess.markRuntimeRequested(this,missing)
            permissionLauncher.launch(missing)
        }
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
    val embedded = RecorderHost.embedded(context)
    val appVersion = remember(context) { GCamUpdater.installedVersion(context) }
    var uiScale by remember { mutableFloatStateOf(RecorderSettings.loadUiScale(context)) }
    var language by remember { mutableIntStateOf(GCamLanguage.load(context)) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var updateAvailable by remember { mutableStateOf(false) }
    val recorder by RecorderRepository.state.collectAsState()
    if (embedded) {
        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density * 1.6f, baseDensity.fontScale),
            LocalGCamEnglish provides GCamLanguage.isEnglish(context, language)
        ) { EmbeddedRecorderApp(recorder) }
        return
    }
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
        if (!embedded) TopAppBar(title = { Text(GCamLanguage.string(context, R.string.gcam_title_version, appVersion)) },
            actions = {
                Box {
                    TextButton(onClick = { languageMenuExpanded = true }) {
                        Text(if (LocalGCamEnglish.current) "EN" else "RU")
                    }
                    DropdownMenu(expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false }) {
                        listOf(0 to GCamLanguage.string(context, R.string.gcam_system), 1 to GCamLanguage.string(context, R.string.gcam_russian), 2 to "English").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                languageMenuExpanded = false
                                chooseLanguage(value)
                            })
                        }
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
        val tabs = if (embedded) listOf(GCamLanguage.string(context, R.string.gcam_cameras_recording), GCamLanguage.string(context, R.string.gcam_recordings))
            else listOf(GCamLanguage.string(context, R.string.gcam_cameras_recording), GCamLanguage.string(context, R.string.gcam_recordings), if (updateAvailable) GCamLanguage.string(context, R.string.gcam_update_2) else GCamLanguage.string(context, R.string.gcam_update))
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
    } }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when (tab) {
            0 -> RecorderScreen(recorder, uiScale, language,
                onUiScaleChange = {
                    uiScale = it
                    RecorderSettings.saveUiScale(context, it)
                }, onLanguageChange = ::chooseLanguage)
            1 -> RecordingsScreen(recorder.recording, recorder.warning,
                recorder.recording && recorder.cameras.values.any { it.recording }, recorder.warningAtMs)
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
    var status by remember { mutableStateOf(GCamLanguage.string(context, R.string.gcam_checking_for_updates)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun check() {
        if (!GCamUpdater.supportsUpdates(context)) {
            status = GCamLanguage.string(context, R.string.gcam_this_is_a_debug_build_updates_are_available_after_installing_a_re)
            return
        }
        busy = true
        scope.launch {
            runCatching { GCamUpdater.latestRelease(context) }
                .onSuccess { found ->
                    release = found
                    downloaded = null
                    status = if (found == null) GCamLanguage.string(context, R.string.gcam_you_re_up_to_date) else GCamLanguage.string(context, R.string.gcam_version_1_s_is_available, found.tag)
                }
                .onFailure { status = GCamLanguage.string(context, R.string.gcam_update_check_failed_1_s, it.message) }
            busy = false
        }
    }

    LaunchedEffect(Unit) { check() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_update_gcam)) }
        item { Text(GCamLanguage.string(context, R.string.gcam_installed_version_1_s, GCamUpdater.installedVersion(context))) }
        item { Text(GCamLanguage.display(context, status)) }
        item {
            OutlinedButton(onClick = { check() }, enabled = !busy && GCamUpdater.supportsUpdates(context)) {
                Text(GCamLanguage.string(context, R.string.gcam_check_for_updates))
            }
        }
        release?.let { current ->
            if (current.notes.isNotBlank()) item {
                Text(GCamLanguage.string(context, R.string.gcam_what_s_new_in_1_s, current.tag), style = MaterialTheme.typography.titleMedium)
                Text(current.notes)
            }
            item {
                if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else if (downloaded == null) Button(onClick = {
                    busy = true
                    progress = 0f
                    status = GCamLanguage.string(context, R.string.gcam_downloading_1_s, current.tag)
                    scope.launch {
                        runCatching { GCamUpdater.download(context, current) { progress = it } }
                            .onSuccess { file ->
                                downloaded = file
                                status = GCamLanguage.string(context, R.string.gcam_apk_verified_you_can_install_1_s, current.tag)
                            }
                            .onFailure { status = GCamLanguage.string(context, R.string.gcam_download_failed_1_s, it.message) }
                        busy = false
                    }
                }) { Text(GCamLanguage.string(context, R.string.gcam_download_update)) }
                else Button(onClick = {
                    runCatching {
                        if (GCamUpdater.canInstall(context)) {
                            GCamUpdater.launchInstaller(context, downloaded!!)
                        } else {
                            status = GCamLanguage.string(context, R.string.gcam_allow_installs_from_gcam_then_tap_the_button_again)
                            GCamUpdater.openInstallPermission(context)
                        }
                    }
                        .onFailure { status = GCamLanguage.string(context, R.string.gcam_could_not_open_installer_1_s, it.message) }
                }, enabled = !recording) { Text(GCamLanguage.string(context, R.string.gcam_install_1_s, current.tag)) }
            }
            if (recording) item {
                Text(GCamLanguage.string(context, R.string.gcam_recording_is_in_progress_stop_it_before_installing_so_mp4_files_a))
            }
        }
        item { Text(GCamLanguage.string(context, R.string.gcam_confirm_installation_in_the_android_system_dialog_the_app_will_re), fontSize = 12.sp) }
    }
}

@Composable private fun RecorderScreen(state: RecorderState, uiScale: Float, language: Int,
                                       onUiScaleChange: (Float) -> Unit, onLanguageChange: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val embedded = RecorderHost.embedded(context)
    var captureAvailable by remember { mutableStateOf(RecorderHost.captureAvailable(context)) }
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
                    .getOrElse { it.message ?: GCamLanguage.string(context, R.string.gcam_usb_error) }
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
    var storage by remember(config.storage, externalFolderRevision) {
        mutableStateOf(StorageStats(-1L, -1L, 0L))
    }
    var storageRevision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { storageRevision++ }
    LaunchedEffect(config.storage, externalFolderRevision, driveListRevision, storageRevision) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { RecordingStore.storageStats(context, config.storage) }.getOrNull()
            }
            if (snapshot != null) storage = snapshot
            delay(5_000)
        }
    }
    val externalError = remember(config.storage, externalFolderRevision) {
        if (config.storage == RecordingStorage.EXTERNAL) RecordingStore.validateExternal(context) else null
    }
    val maxArchiveGb = RecordingStore.maxArchiveGb(storage)
    // Keep the saved quota editable while USB mounts or access to old clips is being restored.
    // Missing archive entries must never silently overwrite the user's selected limit.
    val sliderMaxGb = ArchiveRetention.sliderMaxGb(config.storageLimitGb, maxArchiveGb)
    val minArchiveGb = 1
    var runtimeConfig by remember { mutableStateOf(GCamRuntimeSettings.load(context)) }
    var selectedOverlayPosition by remember { mutableIntStateOf(0) }
    var anhudAccess by remember { mutableStateOf(CaptureClientTrust.status(context)) }
    var blindAccess by remember { mutableStateOf(CaptureClientTrust.blindStatus(context)) }
    var panelAccess by remember { mutableStateOf(CaptureClientTrust.panelStatus(context)) }
    var anhudAccessMessage by remember { mutableStateOf<String?>(null) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var locationAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val enabled = !state.recording
    LaunchedEffect(state.recording) { if (!state.recording) RecorderRepository.update(state.copy(usedBytes = RecordingStore.usedBytes(context))) }
    LaunchedEffect(Unit) {
        while (true) {
            captureAvailable = RecorderHost.captureAvailable(context)
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
            if (!embedded) panelAccess = CaptureClientTrust.panelStatus(context)
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
        RecorderRepository.update(RecorderRepository.state.value.copy(recording = true, status = GCamLanguage.string(context, R.string.gcam_preparing_to_record)))
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { controllerToClose?.close() }
                RecorderDiagnostics.record(context, "preview_closed start_service=true")
                RecorderSettings.save(context, config)
                DashcamService.start(context, config)
            }.onFailure {
                RecorderDiagnostics.record(context, "record_button_error type=${it.javaClass.simpleName} message=${it.message}")
                RecorderRepository.update(RecorderRepository.state.value.copy(recording = false,
                    status = GCamLanguage.string(context, R.string.gcam_could_not_start_recording_1_s, it.message)))
            }
            preparing = false
        }
    }
    if (showQualityWarning) AlertDialog(
        onDismissRequest = { showQualityWarning = false },
        title = { Text(GCamLanguage.string(context, R.string.gcam_high_workload)) },
        text = { Text(GCamLanguage.string(context, R.string.gcam_four_cameras_at_1280_800_may_overload_the_g636_and_drop_frames_re)) },
        confirmButton = { TextButton(onClick = { showQualityWarning = false; launchRecording() }) { Text(GCamLanguage.string(context, R.string.gcam_record_anyway)) } },
        dismissButton = { TextButton(onClick = { showQualityWarning = false }) { Text(GCamLanguage.string(context, R.string.gcam_back)) } }
    )
    if (showInternalWarning) AlertDialog(
        onDismissRequest = { showInternalWarning = false },
        title = { Text(GCamLanguage.string(context, R.string.gcam_record_to_internal_storage)) },
        text = { Text(GCamLanguage.string(context, R.string.gcam_long_four_camera_recordings_continuously_write_to_internal_storag)) },
        confirmButton = { TextButton(onClick = {
            RecorderSettings.saveStorage(context, RecordingStorage.INTERNAL)
            config = config.copy(storage = RecordingStorage.INTERNAL)
            RecorderSettings.save(context, config)
            showInternalWarning = false
        }) { Text(GCamLanguage.string(context, R.string.gcam_i_understand)) } },
        dismissButton = { TextButton(onClick = { showInternalWarning = false }) { Text(GCamLanguage.string(context, R.string.gcam_cancel)) } }
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Surface(color = if (state.recording) Color(0xFF102C26) else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(GCamLanguage.display(context, state.status), color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface)
                state.cameras.values.sortedBy { it.inputId }.forEach { c ->
                    val mode = when (c.zeroCopy) { true -> "ZERO-COPY"; false -> "PBO fallback"; null -> GCamLanguage.string(context, R.string.gcam_checking) }
                    val pause = if (c.maxGapMs > 0) GCamLanguage.string(context, R.string.gcam_max_gap_1_s_ms, c.maxGapMs.toInt()) else ""
                    Text("${recorderCameraNames[c.inputId]?.let { GCamLanguage.display(context, it) }}: $mode · ${"%.1f".format(Locale.US, c.fps)} fps$pause${c.error?.let { " · $it" } ?: ""}", fontSize = 12.sp, color = if (state.recording) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        } }
        item { ArchiveAccessCard(onlyWarning = true) }
        state.warning?.let { warning ->
            item { RecorderWarningBanner(warning,
                state.recording && state.cameras.values.any { it.recording }, state.warningAtMs) }
        }
        item {
            if (!captureAvailable) {
                if (embedded) {
                    SectionTitle(GCamLanguage.string(context, R.string.gcam_standalone_gcam_is_using_the_cameras))
                    Text(GCamLanguage.string(context, R.string.gcam_stop_recording_in_standalone_gcam_and_disable_it_in_android_setti))
                    val legacyPackage = RecorderHost.legacyCapturePackage(context)
                    if (legacyPackage != null) OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$legacyPackage"))) }
                    }) { Text(GCamLanguage.string(context, R.string.gcam_open_android_settings_for_gcam)) }
                } else if (RecorderHost.canRestoreLegacyCapture(context)) {
                    SectionTitle(GCamLanguage.string(context, R.string.gcam_cameras_are_disabled))
                    OutlinedButton(onClick = {
                        RecorderHost.restoreLegacyCapture(context)
                        captureAvailable = RecorderHost.captureAvailable(context)
                        GCamRuntimeController.startAfterManualLaunch(context)
                    }) { Text(GCamLanguage.string(context, R.string.gcam_restore_camera_control)) }
                } else Text(GCamLanguage.string(context, R.string.gcam_cameras_moved_to_gpanel_the_archive_remains_available_here))
            } else if (!state.recording && !preparing && !(runtimeConfig.cameraOverlay && overlayAllowed)) MultiCameraGrid(
                onMounted = { previewController = it },
                onClosed = { closed -> if (previewController === closed) previewController = null }
            )
            else if (!state.recording && !preparing) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text(GCamLanguage.string(context, R.string.gcam_cameras_are_shown_in_a_floating_window_above_other_apps), modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
            else Surface(color = Color(0xFF1B5E20), shape = MaterialTheme.shapes.medium) {
                Text(if (preparing) GCamLanguage.string(context, R.string.gcam_stopping_preview_and_starting_recording) else GCamLanguage.display(context, state.status),
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
                    Text(GCamLanguage.string(context, R.string.gcam_why_is_there_no_image), style = MaterialTheme.typography.titleMedium)
                    Text(GCamLanguage.string(context, R.string.gcam_the_factory_360_view_is_separate_check_the_live_side_cameras_abov), fontSize = 13.sp)
                    Text(GCamLanguage.string(context, R.string.gcam_hud_1_s, ImagePathAdvice.explain(context, "ANHUD", adviceState(anhudAccess, hudHealth))), fontSize = 13.sp)
                    if (embedded) Text(GCamLanguage.string(context, R.string.gcam_cluster_display_settings_are_in_the_cameras_tab), fontSize = 13.sp)
                    else Text(GCamLanguage.string(context, R.string.gcam_cluster_1_s, ImagePathAdvice.explain(context, "gBlind", adviceState(blindAccess, blindHealth))), fontSize = 13.sp)
                }
            }
        }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_cameras_to_record)) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { cameraInputs.forEach { c ->
            FilterChip(enabled = enabled, selected = c.id in config.cameraIds, onClick = { config = config.copy(cameraIds = config.cameraIds.toMutableSet().apply { if (!add(c.id)) remove(c.id) }) }, label = { Text(GCamLanguage.string(context, c.title)) }, modifier = Modifier.weight(1f))
        } } }
        item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_recommended_settings))
            val four = config.cameraIds.size >= 3
            Text(if (four) GCamLanguage.string(context, R.string.gcam_for_3_4_cameras_640_400_15_fps_2_mbps_per_camera) else
                GCamLanguage.string(context, R.string.gcam_for_1_2_cameras_960_600_20_fps_3_mbps_per_camera), fontSize = 13.sp)
            OutlinedButton(onClick = {
                config = if (four) config.copy(width = 640, height = 400, fps = 15, bitrateMbps = 2)
                else config.copy(width = 960, height = 600, fps = 20, bitrateMbps = 3)
                RecorderSettings.save(context, config)
            }, enabled = enabled) { Text(GCamLanguage.string(context, R.string.gcam_apply)) }
        }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_recording_resolution)) }
        item { ChoiceRow(listOf(640 to "640×400", 960 to "960×600", 1280 to "1280×800"), config.width, enabled) { width ->
            config = config.copy(width = width, height = when (width) { 640 -> 400; 1280 -> 800; else -> 600 })
        } }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_recording_frame_rate)) }
        item { ChoiceRow(listOf(15 to "15 FPS", 20 to "20 FPS", 25 to "25 FPS"), config.fps, enabled) { config = config.copy(fps = it) } }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_h_264_bitrate_per_camera)) }
        item { ChoiceRow(listOf(2 to GCamLanguage.string(context, R.string.gcam_message_2_mbps), 3 to GCamLanguage.string(context, R.string.gcam_message_3_mbps), 4 to GCamLanguage.string(context, R.string.gcam_message_4_mbps), 6 to GCamLanguage.string(context, R.string.gcam_message_6_mbps)), config.bitrateMbps, enabled) { config = config.copy(bitrateMbps = it) } }
        if (config.cameraIds.size == 4 && config.width == 1280) item {
            Text(GCamLanguage.string(context, R.string.gcam_four_cameras_at_maximum_resolution_may_cause_high_load_lower_fps),
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_segment_length)) }
        item { ChoiceRow(listOf(1 to GCamLanguage.string(context, R.string.gcam_message_1_min), 2 to GCamLanguage.string(context, R.string.gcam_message_2_min), 3 to GCamLanguage.string(context, R.string.gcam_message_3_min), 5 to GCamLanguage.string(context, R.string.gcam_message_5_min)), config.segmentMinutes, enabled) { config = config.copy(segmentMinutes = it) } }
        item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_video_storage))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = config.storage == RecordingStorage.EXTERNAL, enabled = enabled,
                    onClick = {
                        RecorderSettings.saveStorage(context, RecordingStorage.EXTERNAL)
                        config = config.copy(storage = RecordingStorage.EXTERNAL)
                        RecorderSettings.save(context, config)
                    }, label = { Text("USB") }, modifier = Modifier.weight(1f))
                FilterChip(selected = config.storage == RecordingStorage.INTERNAL, enabled = enabled,
                    onClick = { showInternalWarning = true }, label = { Text(GCamLanguage.string(context, R.string.gcam_internal)) }, modifier = Modifier.weight(1f))
            }
            if (config.storage == RecordingStorage.EXTERNAL) {
                val selectedId = remember(externalFolderRevision, driveListRevision) {
                    RecordingStore.selectedDrive(context)?.id ?: RecorderSettings.externalDriveId(context)
                }
                Text(GCamLanguage.string(context, R.string.gcam_recording_usb_drive), fontSize = 14.sp)
                if (removableDrives.isEmpty()) Text(GCamLanguage.string(context, R.string.gcam_no_connected_usb_drives_found), fontSize = 12.sp)
                removableDrives.forEach { drive ->
                    val selected = selectedId.equals(drive.id, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        enabled = enabled,
                        onClick = { chooseDrive(drive) },
                        label = { Text(GCamLanguage.string(context, if (selected) R.string.gcam_drive_selected else R.string.gcam_drive_select, drive.name, drive.id)) }
                    )
                }
                removableDrives.firstOrNull { it.id.equals(selectedId, ignoreCase = true) }?.let { drive ->
                    Text(GCamLanguage.string(context, R.string.gcam_saving_to_1_s_2_s_3_s, drive.name, drive.id, RecordingStore.archivePath(context)), fontSize = 13.sp)
                }
                OutlinedButton(onClick = { driveListRevision++ }, enabled = enabled) {
                    Text(GCamLanguage.string(context, R.string.gcam_refresh_usb_drives))
                }
                if (selectedId != null && removableDrives.none { it.id.equals(selectedId, ignoreCase = true) }) {
                    Text(GCamLanguage.string(context, R.string.gcam_previously_selected_usb_drive_1_s_is_not_connected, selectedId), fontSize = 12.sp)
                }
                Text(GCamLanguage.string(context, R.string.gcam_usb_storage_explanation, RecordingStore.archivePath(context)), fontSize = 12.sp)
                (folderError ?: externalError)?.let {
                    Text(GCamLanguage.display(context, it), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            } else Text(GCamLanguage.string(context, R.string.gcam_long_recordings_wear_out_internal_storage_usb_is_recommended),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_archive_limit_1_s_gb_2_s, config.storageLimitGb, maxArchiveGb?.let { GCamLanguage.string(context, R.string.gcam_archive_limit_available, it) } ?: ""))
            Slider(config.storageLimitGb.toFloat(), { config = config.copy(storageLimitGb = it.toInt()) },
                valueRange = minArchiveGb.toFloat()..sliderMaxGb.coerceAtLeast(minArchiveGb).toFloat(), enabled = enabled)
            if (storage.freeBytes >= 0) {
                if (storage.totalBytes >= 0) Text(GCamLanguage.string(context, R.string.gcam_selected_drive_1_s_of_2_s_used_3_s_free, formatBytes(context, storage.totalBytes - storage.freeBytes), formatBytes(context, storage.totalBytes), formatBytes(context, storage.freeBytes)), fontSize = 13.sp)
                else Text(GCamLanguage.string(context, R.string.gcam_selected_drive_1_s_total_capacity_was_not_reported_free, formatBytes(context, storage.freeBytes)), fontSize = 13.sp)
                Text(GCamLanguage.string(context, R.string.gcam_gcam_recordings_1_s_maximum_free_space_recordings_2_gb_reserve, formatBytes(context, storage.recordingBytes)), fontSize = 13.sp)
            } else Text(GCamLanguage.string(context, R.string.gcam_free_space_information_is_unavailable_your_saved_limit_is_retaine), fontSize = 12.sp)
            Text(GCamLanguage.string(context, R.string.gcam_approximately_1_s_h_of_recording, "%.1f".format(Locale.US, hours)), fontSize = 13.sp)
        }
        item {
            ArchiveAccessCard()
        }
        item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_video_overlays))
            RuntimeSwitchRow(GCamLanguage.string(context, R.string.gcam_date_and_time), GCamLanguage.string(context, R.string.gcam_drawn_on_the_frame_without_cpu_re_encoding), config.stampTime, enabled) {
                config = config.copy(stampTime = it)
                RecorderSettings.save(context, config)
            }
            RuntimeSwitchRow(GCamLanguage.string(context, R.string.gcam_gps_coordinates), GCamLanguage.string(context, R.string.gcam_shown_only_when_a_recent_location_is_available), config.stampGps, enabled) {
                config = config.copy(stampGps = it)
                RecorderSettings.save(context, config)
                if (it) (context as? MainActivity)?.requestLocationPermission()
            }
            RuntimeSwitchRow(GCamLanguage.string(context, R.string.gcam_gps_speed), GCamLanguage.string(context, R.string.gcam_gps_speed_not_vehicle_can_speed), config.stampSpeed, enabled) {
                config = config.copy(stampSpeed = it)
                RecorderSettings.save(context, config)
                if (it) (context as? MainActivity)?.requestLocationPermission()
            }
            if ((config.stampGps || config.stampSpeed) && !locationAllowed) {
                Text(GCamLanguage.string(context, R.string.gcam_precise_location_permission_is_missing_video_will_show_gps_km_h), fontSize = 12.sp)
            }
        }
        item { SectionTitle(GCamLanguage.string(context, R.string.gcam_interface_scale_1_s, (uiScale * 100).toInt()))
            Slider(uiScale, onUiScaleChange, valueRange = 1.3f..1.8f, steps = 9)
        }
        if (!embedded) item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_app_language))
            ChoiceRow(listOf(0 to GCamLanguage.string(context, R.string.gcam_system), 1 to GCamLanguage.string(context, R.string.gcam_russian), 2 to "English"),
                language, true, onLanguageChange)
        }
        item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_auto_start_indicators))
            Spacer(Modifier.height(6.dp))
            RuntimeSwitchRow(
                title = GCamLanguage.string(context, R.string.gcam_auto_start_gcam),
                subtitle = GCamLanguage.string(context, R.string.gcam_start_the_anhud_camera_link_after_boot),
                checked = runtimeConfig.autostart
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(
                    autostart = checked,
                    recordingAutostart = checked && runtimeConfig.recordingAutostart
                )
                GCamRuntimeSettings.save(context, runtimeConfig)
            }
            RuntimeSwitchRow(
                title = GCamLanguage.string(context, R.string.gcam_auto_start_recording),
                subtitle = GCamLanguage.string(context, R.string.gcam_start_recording_the_selected_cameras_automatically),
                checked = runtimeConfig.recordingAutostart,
                enabled = runtimeConfig.autostart
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(recordingAutostart = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
            }
            RuntimeSwitchRow(
                title = GCamLanguage.string(context, R.string.gcam_dot_gcam_gblind),
                subtitle = GCamLanguage.string(context, R.string.gcam_cluster_camera_recording_red_no_link_blue_frame_received_recordin),
                checked = runtimeConfig.blindStatusOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(blindStatusOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else GCamRuntimeController.applyOverlaySetting(context)
            }
            RuntimeSwitchRow(
                title = GCamLanguage.string(context, R.string.gcam_dot_gcam_anhud),
                subtitle = GCamLanguage.string(context, R.string.gcam_hud_camera_recording_red_no_link_blue_frame_received_recording_no),
                checked = runtimeConfig.hudStatusOverlay
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(hudStatusOverlay = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (checked && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else GCamRuntimeController.applyOverlaySetting(context)
            }
            RuntimeSwitchRow(
                title = GCamLanguage.string(context, R.string.gcam_dot_gcam_anhud_gblind),
                subtitle = GCamLanguage.string(context, R.string.gcam_split_in_half_hud_on_the_left_cluster_on_the_right_each_half_refl),
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
                title = GCamLanguage.string(context, R.string.gcam_above_other_apps),
                subtitle = if (overlayAllowed) GCamLanguage.string(context, R.string.gcam_movable_floating_view_with_all_four_cameras) else GCamLanguage.string(context, R.string.gcam_display_over_other_apps_permission_is_required),
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
                title = GCamLanguage.string(context, R.string.gcam_floating_record_button),
                subtitle = if (overlayAllowed) GCamLanguage.string(context, R.string.gcam_separate_button_red_camera_error_blue_ready_not_recording_orange)
                    else GCamLanguage.string(context, R.string.gcam_display_over_other_apps_permission_is_required),
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
            Text(GCamLanguage.string(context, R.string.gcam_record_button_size_1_s_dp, runtimeConfig.recordOverlaySizeDp), fontSize = 13.sp)
            Slider(
                value = runtimeConfig.recordOverlaySizeDp.toFloat(),
                onValueChange = { value ->
                    runtimeConfig = runtimeConfig.copy(recordOverlaySizeDp = value.toInt())
                    GCamRuntimeSettings.save(context, runtimeConfig)
                    GCamRuntimeController.updateRecordOverlay(context)
                },
                valueRange = GCamRuntimeSettings.MIN_RECORD_OVERLAY_SIZE_DP.toFloat()..
                    GCamRuntimeSettings.MAX_RECORD_OVERLAY_SIZE_DP.toFloat(),
                enabled = runtimeConfig.recordOverlay && overlayAllowed
            )
            RuntimeSwitchRow(
                title = GCamLanguage.string(context, R.string.gcam_monochrome_record_button),
                subtitle = GCamLanguage.string(context, R.string.gcam_white_icons_triangle_recording_square_ready_pause_usb_not_ready_e),
                checked = runtimeConfig.recordOverlayMonochrome
            ) { checked ->
                runtimeConfig = runtimeConfig.copy(recordOverlayMonochrome = checked)
                GCamRuntimeSettings.save(context, runtimeConfig)
                if (runtimeConfig.recordOverlay) GCamRuntimeController.updateRecordOverlay(context)
            }
            TextButton(onClick = {
                runtimeConfig = GCamRuntimeSettings.withDefaultStatusOverlayPositions(runtimeConfig)
                GCamRuntimeSettings.save(context, runtimeConfig)
                GCamRuntimeController.updateAllOverlayPositions(context)
            }) {
                Text(GCamLanguage.string(context, R.string.gcam_reset_all_dot_positions))
            }
            Text(GCamLanguage.string(context, R.string.gcam_moves_the_three_dots_back_to_their_default_positions_without_chan), fontSize = 12.sp)
            Text(GCamLanguage.string(context, R.string.gcam_floating_element_position), fontSize = 13.sp)
            ChoiceRow(
                options = listOf(0 to "gBlind", 1 to "ANHUD", 2 to GCamLanguage.string(context, R.string.gcam_both), 3 to GCamLanguage.string(context, R.string.gcam_record)),
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
            Text(GCamLanguage.string(context, R.string.gcam_horizontal_1_s, (overlayX * 100).toInt()), fontSize = 13.sp)
            Slider(
                value = overlayX,
                onValueChange = { saveOverlayPosition(x = it) },
                valueRange = 0f..1f
            )
            Text(GCamLanguage.string(context, R.string.gcam_vertical_1_s, (overlayY * 100).toInt()), fontSize = 13.sp)
            Slider(
                value = overlayY,
                onValueChange = { saveOverlayPosition(y = it) },
                valueRange = 0f..1f
            )
            Text(GCamLanguage.string(context, R.string.gcam_you_can_set_the_position_of_a_disabled_element_enabled_elements_m), fontSize = 12.sp)
            Text(GCamLanguage.string(context, R.string.gcam_red_no_link_or_video), color = Color(0xFFF44336), fontSize = 12.sp)
            Text(GCamLanguage.string(context, R.string.gcam_blue_video_received_recording_not_requested), color = Color(0xFF2196F3), fontSize = 12.sp)
            Text(GCamLanguage.string(context, R.string.gcam_orange_video_received_expected_recording_is_not_running), color = Color(0xFFFF9800), fontSize = 12.sp)
            Text(GCamLanguage.string(context, R.string.gcam_green_video_received_and_recording_is_running), color = Color(0xFF4CAF50), fontSize = 12.sp)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(GCamLanguage.string(context, R.string.gcam_open_accessibility_settings))
            }
            Text(
                GCamLanguage.string(context, R.string.gcam_accessibility_is_a_fallback_auto_start_method_for_head_units_wher),
                fontSize = 12.sp
            )
        }
        item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_anhud_camera_access))
            Text(
                when {
                    !anhudAccess.installed -> GCamLanguage.string(context, R.string.gcam_anhud_is_not_installed_reopen_this_page_after_installing_it)
                    anhudAccess.sameSigner -> GCamLanguage.string(context, R.string.gcam_access_granted_automatically_anhud_and_gcam_have_the_same_signing)
                    anhudAccess.approved -> GCamLanguage.string(context, R.string.gcam_access_granted_to_the_installed_anhud_version)
                    anhudAccess.signerChanged -> GCamLanguage.string(context, R.string.gcam_warning_anhud_s_signature_changed_automatic_access_is_off_verify)
                    else -> GCamLanguage.string(context, R.string.gcam_warning_anhud_has_an_unknown_signing_key_automatic_access_is_off)
                },
                fontSize = 13.sp
            )
            if (anhudAccess.installed && !anhudAccess.sameSigner) {
                OutlinedButton(onClick = {
                    anhudAccessMessage = runCatching {
                        if (anhudAccess.approved) {
                            CaptureClientTrust.revoke(context)
                            GCamLanguage.string(context, R.string.gcam_anhud_access_revoked)
                        } else if (CaptureClientTrust.approve(context)) {
                            GCamLanguage.string(context, R.string.gcam_anhud_access_granted)
                        } else {
                            GCamLanguage.string(context, R.string.gcam_anhud_is_no_longer_installed)
                        }
                    }.getOrElse { GCamLanguage.string(context, R.string.gcam_could_not_change_access_1_s, it.message) }
                    anhudAccess = CaptureClientTrust.status(context)
                }) {
                    Text(if (anhudAccess.approved) GCamLanguage.string(context, R.string.gcam_revoke_access) else GCamLanguage.string(context, R.string.gcam_allow_anhud))
                }
            }
            anhudAccessMessage?.let { Text(GCamLanguage.display(context, it), fontSize = 12.sp) }
            Text(GCamLanguage.string(context, R.string.gcam_permission_is_tied_to_the_installed_anhud_signature_not_a_passwor), fontSize = 12.sp)
        }
        if (!embedded) item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_gblind_access_to_cluster_cameras))
            Text(when {
                !blindAccess.installed -> GCamLanguage.string(context, R.string.gcam_gblind_is_not_installed)
                blindAccess.sameSigner -> GCamLanguage.string(context, R.string.gcam_access_granted_automatically_gblind_and_gcam_have_the_same_signin)
                blindAccess.approved -> GCamLanguage.string(context, R.string.gcam_cluster_camera_access_granted)
                blindAccess.signerChanged -> GCamLanguage.string(context, R.string.gcam_warning_gblind_s_signature_changed_automatic_access_is_off_verify)
                else -> GCamLanguage.string(context, R.string.gcam_warning_gblind_has_an_unknown_signing_key_automatic_access_is_off)
            }, fontSize = 13.sp)
            if (blindAccess.installed && !blindAccess.sameSigner) {
                OutlinedButton(onClick = {
                    if (blindAccess.approved) CaptureClientTrust.revokeBlind(context)
                    else CaptureClientTrust.approveBlind(context)
                    blindAccess = CaptureClientTrust.blindStatus(context)
                }) { Text(if (blindAccess.approved) GCamLanguage.string(context, R.string.gcam_revoke_access) else GCamLanguage.string(context, R.string.gcam_allow_gblind)) }
            }
        }
        if (!embedded && panelAccess.installed) item {
            SectionTitle(GCamLanguage.string(context, R.string.gcam_gpanel_cluster_camera_access))
            val allowed = panelAccess.sameSigner || panelAccess.approved
            Text(if (allowed) GCamLanguage.string(context, R.string.gcam_access_granted)
                else GCamLanguage.string(context, R.string.gcam_confirm_access_for_the_installed_gpanel))
            if (!panelAccess.sameSigner) OutlinedButton(onClick = {
                if (panelAccess.approved) CaptureClientTrust.revokePanel(context)
                else CaptureClientTrust.approvePanel(context)
                panelAccess = CaptureClientTrust.panelStatus(context)
            }) {
                Text(if (panelAccess.approved) GCamLanguage.string(context, R.string.gcam_revoke_access)
                    else GCamLanguage.string(context, R.string.gcam_allow_gpanel))
            }
        }
        item { Button(onClick = {
            if (state.recording) DashcamService.stop(context)
            else if (!captureAvailable) return@Button
            else if (config.cameraIds.size == 4 && config.width == 1280) showQualityWarning = true
            else launchRecording()
        }, enabled = !preparing && (state.recording || (captureAvailable && config.cameraIds.isNotEmpty())), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.recording) GCamLanguage.string(context, R.string.gcam_stop_recording) else GCamLanguage.string(context, R.string.gcam_start_recording))
        } }
        item {
            val linkText: (LinkHealth) -> String = { link -> when {
                !link.enabled -> GCamLanguage.string(context, R.string.gcam_off)
                healthNow - link.receivedAt > 6_000 -> GCamLanguage.string(context, R.string.gcam_no_recent_status)
                !link.error.isNullOrBlank() -> GCamLanguage.string(context, R.string.gcam_error_1_s, link.error)
                link.connected && link.video -> GCamLanguage.string(context, R.string.gcam_connected_video_received)
                link.connected -> GCamLanguage.string(context, R.string.gcam_connected_waiting_for_video)
                else -> GCamLanguage.string(context, R.string.gcam_no_link)
            } }
            Text("ANHUD: ${linkText(hudHealth)}", fontSize = 13.sp)
            Text("gBlind: ${linkText(blindHealth)}", fontSize = 13.sp)
            val videoRecording = state.recording && recorderVideoAt > 0 && healthNow - recorderVideoAt <= 6_000L
            val recordText = when {
                videoRecording -> GCamLanguage.string(context, R.string.gcam_recording)
                state.recording -> GCamLanguage.display(context, state.status)
                config.storage == RecordingStorage.EXTERNAL && usbError != null -> GCamLanguage.string(context, R.string.gcam_usb_not_ready_1_s, usbError)
                state.status.startsWith("Запись не запущена") || state.status.startsWith("Не удалось") -> GCamLanguage.display(context, state.status)
                config.storage == RecordingStorage.INTERNAL -> GCamLanguage.string(context, R.string.gcam_not_recording_internal_storage_selected)
                else -> GCamLanguage.string(context, R.string.gcam_not_recording_usb_ready)
            }
            Text(GCamLanguage.string(context, R.string.gcam_recorder_1_s, recordText), fontSize = 13.sp)
        }
        item {
            OutlinedButton(
                onClick = { runCatching { RecorderDiagnostics.share(context) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(GCamLanguage.string(context, R.string.gcam_share_recording_diagnostics)) }
            Text(GCamLanguage.string(context, R.string.gcam_the_log_survives_reboots_and_is_capped_at_2_mb_share_it_after_a_t), fontSize = 12.sp)
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

@Composable internal fun MultiCameraGrid(
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(GCamLanguage.string(context, R.string.gcam_connecting)) }
    var diagnostics by remember { mutableStateOf<CameraDiagnostics?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { pollJob?.cancel(); controller.stop(input.id) } }
    val embedded = RecorderHost.embedded(LocalContext.current)
    val tile = modifier.aspectRatio(1.6f)
    Box(if (embedded) tile.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant)
        else tile.background(Color.Black)) {
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
        Surface(color = if (embedded) Color(0xE8102133) else Color(0xAA000000),
            shape = if (embedded) MaterialTheme.shapes.small else androidx.compose.ui.graphics.RectangleShape,
            modifier = Modifier.align(Alignment.TopStart).padding(if (embedded) 8.dp else 0.dp)) {
            Column(Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
                Text(GCamLanguage.string(context, input.title), color = Color.White, fontSize = if (embedded) 13.sp else 12.sp)
                diagnostics?.takeIf { !embedded }?.let { d ->
                    Text(
                        GCamLanguage.string(context, R.string.gcam_frame_diagnostics, if (d.zeroCopy) "ZERO-COPY" else "PBO fallback", "%.1f".format(Locale.US, d.fps), d.maxGapMs.toInt()),
                        color = if (d.zeroCopy) Color(0xFF69F0AE) else Color(0xFFFFCC80),
                        fontSize = 8.sp,
                        maxLines = 1
                    )
                }
                Text(GCamLanguage.display(context, status), color = Color.White, fontSize = if (embedded) 11.sp else 8.sp, maxLines = 1)
            }
        }
    }
}

@Composable private fun Text(text: String, modifier: Modifier = Modifier,
                             color: Color = Color.Unspecified,
                             fontSize: TextUnit = TextUnit.Unspecified,
                             style: TextStyle = LocalTextStyle.current,
                             maxLines: Int = Int.MAX_VALUE) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier, color = color, fontSize = fontSize,
        style = style, maxLines = maxLines
    )
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable private fun ArchiveAccessCard(onlyWarning: Boolean = false) {
    val context = LocalContext.current
    // The embedded recorder has one permission card above its recording/archive content.
    if (RecorderHost.embedded(context)) return
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ArchiveAccess.isGranted()) }
    var checking by remember { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ArchiveAccess.isGranted()
        if (granted) settingsUnavailable = false
        ArchiveAccess.recordState(context)
    }
    LaunchedEffect(Unit) {
        if (!RecorderHost.embedded(context)) {
            checking = !granted
            granted = withContext(Dispatchers.IO) { ArchiveAccess.tryGrantWithRoot(context) }
            checking = false
        }
        while (true) {
            delay(2_000)
            val actual = ArchiveAccess.isGranted()
            if (actual != granted) ArchiveAccess.recordState(context)
            granted = actual
        }
    }
    val embedded = RecorderHost.embedded(context)
    if (!granted && embedded) {
        if (!onlyWarning) Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(GCamLanguage.string(context, R.string.gcam_archive_access))
                Text(GCamLanguage.string(context, R.string.gcam_deleting_recordings_from_previous_gpanel_installs_may_require_sto), fontSize = 13.sp)
                OutlinedButton(onClick = { settingsUnavailable = !ArchiveAccess.openSettings(context) }) {
                    Text(GCamLanguage.string(context, R.string.gcam_open_storage_settings))
                }
                if (settingsUnavailable) Text(GCamLanguage.string(context, R.string.gcam_storage_settings_are_unavailable_on_this_device), fontSize = 12.sp)
            }
        }
        return
    }
    if (granted) {
        if (!onlyWarning) Surface(color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(GCamLanguage.string(context, R.string.gcam_automatic_archive_cleanup))
                Text(GCamLanguage.string(context, R.string.gcam_access_granted_old_recordings_are_deleted_automatically_to_stay_w), fontSize = 13.sp)
            }
        }
        return
    }
    val accent = Color(0xFFFFC978)
    Surface(color = Color(0xFF241E32), contentColor = Color(0xFFF4EDF9),
        shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, accent.copy(alpha = .55f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(accent.copy(alpha = .14f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("↻", fontSize = 30.sp, color = accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(GCamLanguage.string(context, R.string.gcam_archive_action_required), fontSize = 11.sp, color = accent)
                    Text(GCamLanguage.string(context, R.string.gcam_allow_automatic_cleanup), style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(GCamLanguage.string(context, R.string.gcam_without_access_to_older_files_recording_may_stop_when_the_archive), fontSize = 14.sp)
            Text(GCamLanguage.string(context, R.string.gcam_android_gcam_1_s, RecordingStore.archivePath(context)),
                fontSize = 12.sp, color = Color(0xFFCEC4D9))
            Button(enabled = !checking, colors = ButtonDefaults.buttonColors(
                containerColor = accent, contentColor = Color(0xFF241E32)),
                shape = RoundedCornerShape(12.dp), onClick = {
                    checking = true
                    scope.launch {
                        if (!RecorderHost.embedded(context))
                            granted = withContext(Dispatchers.IO) { ArchiveAccess.tryGrantWithRoot(context, retry = true) }
                        checking = false
                        if (!granted) settingsUnavailable = !ArchiveAccess.openSettings(context)
                    }
                }) {
                Text(if (checking) GCamLanguage.string(context, R.string.gcam_checking_access) else GCamLanguage.string(context, R.string.gcam_grant_access))
            }
            if (settingsUnavailable) Text(
                GCamLanguage.string(context, R.string.gcam_this_head_unit_has_no_access_settings_screen_grant_access_through, context.packageName),
                fontSize = 12.sp, color = accent)
        }
    }
}

@Composable private fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { (v, title) -> FilterChip(selected = selected == v, enabled = enabled, onClick = { onSelect(v) }, label = { Text(title) }, modifier = Modifier.weight(1f)) } }
}

@Composable private fun RecorderWarningBanner(warning: String, recordingActive: Boolean, warningAtMs: Long = 0L) {
    val context = LocalContext.current
    Surface(color = Color(0xFFFFE0B2), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(GCamLanguage.string(context, R.string.gcam_recording_warning), style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF4E2600))
            Text(EmbeddedRecorderAccess.displayMessage(context, warning), color = Color(0xFF4E2600))
            if (warningAtMs > 0) Text(GCamLanguage.string(context, R.string.gcam_last_recording_failure,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    context.resources.configuration.locales[0]).format(Date(warningAtMs))),
                color = Color(0xFF4E2600), fontSize = 12.sp)
            Text(if (recordingActive) GCamLanguage.string(context, R.string.gcam_recording_resumed) else GCamLanguage.string(context, R.string.gcam_recording_is_stopped_or_recovering),
                color = Color(0xFF4E2600), fontSize = 13.sp)
            TextButton(onClick = { runCatching { RecorderDiagnostics.share(context) } }) {
                Text(GCamLanguage.string(context, R.string.gcam_share_diagnostics))
            }
        }
    }
}

@Composable internal fun RecordingsScreen(recording: Boolean, warning: String?, recordingActive: Boolean, warningAtMs: Long = 0L) {
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
        warning?.let { message -> item { RecorderWarningBanner(message, recordingActive, warningAtMs) } }
        item { ArchiveAccessCard() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(GCamLanguage.string(context, R.string.gcam_archive_summary, RecordingStore.archivePath(context), timelines.size, recordings.size, formatBytes(context, recordings.sumOf { it.sizeBytes })), style = MaterialTheme.typography.titleMedium)
                if (!selecting) {
                    Button(onClick = { selecting = true }, enabled = recordings.isNotEmpty()) {
                        Text(GCamLanguage.string(context, R.string.gcam_select_files))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            selectedUris = recordings.mapTo(HashSet()) { it.uri }
                        }, enabled = !deleting && recordings.isNotEmpty()) { Text(GCamLanguage.string(context, R.string.gcam_select_all)) }
                        TextButton(onClick = { selectedUris = emptySet() },
                            enabled = !deleting && selectedUris.isNotEmpty()) { Text(GCamLanguage.string(context, R.string.gcam_deselect_all)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            confirmDelete = recordings.filter { it.uri in selectedUris }
                        }, enabled = !deleting && !recording && selectedUris.isNotEmpty()) {
                            Text(if (deleting) GCamLanguage.string(context, R.string.gcam_deleting) else GCamLanguage.string(context, R.string.gcam_delete_selected_1_s, selectedUris.size))
                        }
                        TextButton(onClick = { selecting = false; selectedUris = emptySet() },
                            enabled = !deleting) { Text(GCamLanguage.string(context, R.string.gcam_cancel)) }
                    }
                    if (recording) Text(GCamLanguage.string(context, R.string.gcam_stop_recording_before_deleting_files), fontSize = 12.sp)
                }
            }
        }
        if (timelines.isEmpty()) item { Text(GCamLanguage.string(context, R.string.gcam_no_recordings_yet)) }
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
                            Text(recorderCameraNames[timeline.inputId]?.let { GCamLanguage.display(context, it) } ?: GCamLanguage.string(context, R.string.gcam_camera_1_s, timeline.inputId))
                            Text(GCamLanguage.string(context, R.string.gcam_recording_metadata, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, if (LocalGCamEnglish.current) Locale.ENGLISH else Locale.forLanguageTag("ru")).format(Date(timeline.startedAt)), formatDuration(timeline.durationMs), timeline.parts.size, formatBytes(context, timeline.sizeBytes)), fontSize = 12.sp)
                        }
                        TextButton(onClick = { playing = timeline }) { Text(GCamLanguage.string(context, R.string.gcam_watch)) }
                    }
                    TextButton(onClick = { expanded = if (key in expanded) expanded - key else expanded + key }) {
                        Text(if (key in expanded) GCamLanguage.string(context, R.string.gcam_hide_files) else GCamLanguage.string(context, R.string.gcam_show_files))
                    }
                    if (key in expanded) timeline.parts.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Checkbox(checked = r.uri in selectedUris,
                                onCheckedChange = { checked ->
                                    selectedUris = if (checked) selectedUris + r.uri else selectedUris - r.uri
                                }, enabled = !deleting)
                            Text("${DateFormat.getTimeInstance(DateFormat.SHORT, if (LocalGCamEnglish.current) Locale.ENGLISH else Locale.forLanguageTag("ru")).format(Date(r.modifiedAt))} · ${formatDuration(r.durationMs)} · ${if (r.storage == RecordingStorage.EXTERNAL) "USB" else GCamLanguage.string(context, R.string.gcam_internal_2)}${if (r.protected) GCamLanguage.string(context, R.string.gcam_protected) else ""}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                            if (!selecting) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { RecordingStore.setProtected(context, r, !r.protected) }
                                        }
                                        result.exceptionOrNull()?.let {
                                            deletionMessage = if (it is SecurityException) ArchiveAccessRequiredException.MESSAGE
                                                else GCamLanguage.string(context, R.string.gcam_could_not_change_file_protection_1_s, it.message)
                                        }
                                        revision++
                                    }
                                }) { Text(if (r.protected) GCamLanguage.string(context, R.string.gcam_unprotect) else GCamLanguage.string(context, R.string.gcam_protect)) }
                                TextButton(onClick = { confirmDelete = listOf(r) }, enabled = !recording && !deleting) { Text(GCamLanguage.string(context, R.string.gcam_delete)) }
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
            title = { Text(GCamLanguage.string(context, R.string.gcam_delete_selected_files)) },
            text = { Text(buildString {
                append(GCamLanguage.string(context, R.string.gcam_permanently_delete_1_s_files_2_s_from_gcam, targets.size, formatBytes(context, targets.sumOf { it.sizeBytes })))
                val protectedCount = targets.count { it.protected }
                if (protectedCount > 0) append(GCamLanguage.string(context, R.string.gcam_protected_files_in_selection_1_s, protectedCount))
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
                        else GCamLanguage.string(context, R.string.gcam_deleted_1_s_of_2_s_files, targets.size - failed.size, targets.size) +
                            if (failed.isNotEmpty()) GCamLanguage.string(context, R.string.gcam_deletion_failed_count, failed.size) else "."
                        revision++
                    }
                }, enabled = !deleting && !recording) { Text(GCamLanguage.string(context, R.string.gcam_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(GCamLanguage.string(context, R.string.gcam_cancel)) } }
        )
    }
    deletionMessage?.let { message ->
        AlertDialog(onDismissRequest = { deletionMessage = null },
            title = { Text(GCamLanguage.string(context, R.string.gcam_recording_archive)) }, text = { Text(EmbeddedRecorderAccess.displayMessage(context, message)) },
            confirmButton = { TextButton(onClick = { deletionMessage = null }) { Text(GCamLanguage.string(context, R.string.gcam_ok)) } })
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
                error = GCamLanguage.string(context, R.string.gcam_playback_error_1_s_2_s, what, extra)
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
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text(GCamLanguage.string(context, R.string.gcam_close)) }
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
                    }) { Text(if (playing) GCamLanguage.string(context, R.string.gcam_pause) else GCamLanguage.string(context, R.string.gcam_play)) }
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

internal fun formatBytes(context: android.content.Context, bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> GCamLanguage.string(context, R.string.gcam_size_gigabytes).format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> GCamLanguage.string(context, R.string.gcam_size_megabytes).format(Locale.US, bytes / (1024.0 * 1024))
    else -> GCamLanguage.string(context, R.string.gcam_size_kilobytes, bytes / 1024)
}
