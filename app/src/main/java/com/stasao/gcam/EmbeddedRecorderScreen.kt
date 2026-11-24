package com.stasao.gcam

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val panelInk = Color(0xFFEDF5FC)
private val panelMuted = Color(0xFF9DB6C9)
private val panelAccent = Color(0xFF66CAFF)
private val panelCard = Color(0xFF102133)
private val panelSelected = Color(0xFF1A394C)
private val panelWarning = Color(0xFFFFC780)
private val panelBackground = Color(0xFF080F19)
private val panelShape = RoundedCornerShape(13.dp)
private val panelColors = darkColorScheme(
    primary=panelAccent, onPrimary=Color(0xFF071A25),
    primaryContainer=panelSelected, onPrimaryContainer=panelInk,
    secondary=panelAccent, onSecondary=Color(0xFF071A25),
    secondaryContainer=panelSelected, onSecondaryContainer=panelInk,
    background=panelBackground, onBackground=panelInk,
    surface=panelCard, onSurface=panelInk,
    surfaceVariant=panelSelected, onSurfaceVariant=panelMuted,
    outline=Color(0xFF365466), error=panelWarning, onError=Color(0xFF301A00)
)

/** gPanel's recorder surface. The standalone recorder keeps its own settings screen. */
@Composable
internal fun EmbeddedRecorderApp(state: RecorderState) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme=panelColors) {
        Surface(Modifier.fillMaxSize(),color=panelBackground,contentColor=panelInk) {
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
                Text(tr(context, R.string.panel_recorder_title), fontSize=24.sp, fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=12.dp, bottom=12.dp), color=panelInk)
                Row(Modifier.fillMaxWidth().padding(bottom=12.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PanelChoice(tr(context, R.string.gcam_cameras_recording), tab==0, true,
                        Modifier.weight(1f)) { tab=0 }
                    PanelChoice(tr(context, R.string.gcam_recordings), tab==1, true,
                        Modifier.weight(1f)) { tab=1 }
                }
                Box(Modifier.weight(1f)) {
                    if(tab==0) EmbeddedRecorderScreen(state)
                    else Column {
                        EmbeddedArchivePermissionsCard()
                        Box(Modifier.weight(1f)) {
                            RecordingsScreen(state.recording, state.warning,
                                state.recording && state.cameras.values.any { it.recording }, state.warningAtMs)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedRecorderScreen(state: RecorderState) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var config by remember { mutableStateOf(RecorderSettings.load(context)) }
    var runtime by remember { mutableStateOf(GCamRuntimeSettings.load(context)) }
    var access by remember { mutableStateOf(EmbeddedRecorderAccess.snapshot(context,config)) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<MultiCameraPreviewController?>(null) }
    var storageRevision by remember { mutableIntStateOf(0) }
    var drives by remember { mutableStateOf(emptyList<RemovableDrive>()) }
    var storage by remember { mutableStateOf(StorageStats(-1,-1,0)) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var extrasOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    val editable=!state.recording && !preparing
    fun change(next:RecorderConfig) {
        config=next
        RecorderSettings.save(context,next)
        error=null
    }
    fun runtimeChange(next:GCamRuntimeConfig) {
        runtime=next
        GCamRuntimeSettings.save(context,next)
        access=EmbeddedRecorderAccess.snapshot(context,config)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        config=RecorderSettings.load(context)
        runtime=GCamRuntimeSettings.load(context)
        EmbeddedRecorderAccess.resumeFloatingFeatures(context)
        storageRevision++
    }
    val latestConfig by rememberUpdatedState(config)
    DisposableEffect(context) { onDispose { RecorderSettings.save(context,latestConfig) } }
    LaunchedEffect(config,storageRevision) {
        while(true) {
            access=EmbeddedRecorderAccess.snapshot(context,config)
            val result=withContext(Dispatchers.IO) {
                Triple(runCatching { RecordingStore.removableDrives(context) }.getOrDefault(emptyList()),
                    runCatching { RecordingStore.storageStats(context,config.storage) }.getOrNull(),
                    if(config.storage==RecordingStorage.EXTERNAL) runCatching { RecordingStore.validateExternal(context) }
                        .getOrElse { it.message ?: tr(context,R.string.gcam_usb_error) } else null)
            }
            drives=result.first
            result.second?.let { storage=it }
            storageError=result.third
            delay(2_000)
        }
    }
    fun start() {
        if(state.recording) { DashcamService.stop(context); return }
        val blocked=EmbeddedRecorderAccess.snapshot(context,config).blocker
        if(blocked!=null) {
            error=blocked
            return
        }
        preparing=true; error=null
        val controller=preview
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if(config.storage==RecordingStorage.EXTERNAL) {
                        RecordingStore.validateExternal(context)?.let { throw IllegalStateException(it) }
                    }
                    controller?.close()
                    RecorderSettings.save(context,config)
                }
                RecorderDiagnostics.record(context,"panel_record_button_start cameras="+config.cameraIds.sorted())
                DashcamService.start(context,config)
            }.onFailure { failure ->
                error=failure.message ?: tr(context,R.string.gcam_could_not_start_recording_1_s,failure.javaClass.simpleName)
                RecorderDiagnostics.record(context,"panel_record_button_error type="+failure.javaClass.simpleName)
            }
            preparing=false
        }
    }
    val failure=error ?: state.warning ?: state.cameras.values.firstNotNullOfOrNull { it.error }
        ?: access.blocker ?: storageError
    val recordingCount=state.cameras.values.count { it.recording && it.frames>0 }
    val idleStatus=state.status.isBlank() || state.status in listOf("Регистратор остановлен","Recorder stopped",
        tr(context,R.string.gcam_recorder_stopped))
    val headline=when {
        preparing -> tr(context,R.string.gcam_preparing_to_record)
        state.recording && recordingCount>0 -> tr(context,R.string.panel_recorder_recording_count,recordingCount,config.cameraIds.size)
        state.recording && state.cameras.isNotEmpty() && failure==null -> tr(context,R.string.panel_recorder_waiting_frames)
        state.recording -> EmbeddedRecorderAccess.displayMessage(context,state.status)
        failure!=null -> EmbeddedRecorderAccess.displayMessage(context,failure)
        !idleStatus -> EmbeddedRecorderAccess.displayMessage(context,state.status)
        else -> tr(context,R.string.panel_recorder_ready)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(bottom=32.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            PanelCard(color=if(state.recording && recordingCount>0) Color(0xFF13362F) else panelCard) {
                Button(onClick=::start, enabled=!preparing,
                    modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),
                    shape=RoundedCornerShape(10.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=panelAccent,contentColor=Color(0xFF071A25))) {
                    Text(tr(context,if(state.recording) R.string.gcam_stop_recording else R.string.gcam_start_recording),
                        fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                }
                Text(headline, color=if(!state.recording && failure!=null) panelWarning else panelInk,
                    fontSize=15.sp,modifier=Modifier.padding(top=10.dp))
                if(state.recording && failure!=null) Text(EmbeddedRecorderAccess.displayMessage(context,failure),
                    color=panelWarning,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
            }
        }
        item { EmbeddedPermissionCard(access) { access=EmbeddedRecorderAccess.snapshot(context,config) } }
        item {
            PanelCard {
                PanelSection(tr(context,R.string.gcam_cameras_to_record))
                BoxWithConstraints {
                    val perRow=if(maxWidth>=620.dp)4 else 2
                    val cameras=listOf(0 to R.string.gcam_left,1 to R.string.gcam_right,
                        2 to R.string.gcam_front,3 to R.string.gcam_rear)
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        cameras.chunked(perRow).forEach { row ->
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                row.forEach { (id,title) ->
                                    PanelChoice(tr(context,title),id in config.cameraIds,editable,Modifier.weight(1f),
                                        subtitle=tr(context,if(id in config.cameraIds) R.string.panel_recorder_camera_selected else R.string.panel_recorder_camera_off)) {
                                        change(config.copy(cameraIds=config.cameraIds.toMutableSet().apply { if(!add(id))remove(id) }))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            PanelCard {
                PanelSection(tr(context,R.string.panel_recorder_quality))
                PanelLabel(tr(context,R.string.gcam_recording_resolution))
                PanelChoices(listOf(640 to "640 × 400",960 to "960 × 600",1280 to "1280 × 800"),
                    config.width,editable) { width ->
                    change(config.copy(width=width,height=when(width) {640->400;1280->800;else->600}))
                }
                PanelLabel(tr(context,R.string.gcam_recording_frame_rate))
                PanelChoices(listOf(15 to "15 FPS",20 to "20 FPS",25 to "25 FPS"),config.fps,editable) {
                    change(config.copy(fps=it))
                }
                PanelLabel(tr(context,R.string.gcam_h_264_bitrate_per_camera))
                PanelChoices(listOf(2 to tr(context,R.string.gcam_message_2_mbps),3 to tr(context,R.string.gcam_message_3_mbps),
                    4 to tr(context,R.string.gcam_message_4_mbps),6 to tr(context,R.string.gcam_message_6_mbps)),
                    config.bitrateMbps,editable) { change(config.copy(bitrateMbps=it)) }
                if(config.cameraIds.size>=3 && config.width==1280) Text(
                    tr(context,R.string.gcam_four_cameras_at_maximum_resolution_may_cause_high_load_lower_fps),
                    color=panelWarning,fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
                TextButton(enabled=editable,onClick={
                    change(if(config.cameraIds.size>=3) config.copy(width=640,height=400,fps=15,bitrateMbps=2)
                        else config.copy(width=960,height=600,fps=20,bitrateMbps=3))
                }) { Text(tr(context,R.string.panel_recorder_use_recommended),color=panelAccent) }
                PanelLabel(tr(context,R.string.gcam_segment_length))
                PanelChoices(listOf(1 to tr(context,R.string.gcam_message_1_min),2 to tr(context,R.string.gcam_message_2_min),
                    3 to tr(context,R.string.gcam_message_3_min),5 to tr(context,R.string.gcam_message_5_min)),
                    config.segmentMinutes,editable) { change(config.copy(segmentMinutes=it)) }
            }
        }
        item {
            PanelCard {
                TextButton(onClick={previewOpen=!previewOpen},modifier=Modifier.fillMaxWidth()) {
                    Text(tr(context,if(previewOpen) R.string.panel_recorder_hide_preview else R.string.panel_recorder_show_preview),
                        color=panelAccent,fontSize=16.sp)
                }
                if(previewOpen) {
                    if(!state.recording && !preparing && access.previewAvailable && !runtime.cameraOverlay) {
                        MultiCameraGrid(onMounted={ preview=it },onClosed={ if(preview===it) preview=null })
                    } else Text(tr(context,when {
                        state.recording -> R.string.panel_recorder_preview_recording
                        runtime.cameraOverlay -> R.string.gcam_cameras_are_shown_in_a_floating_window_above_other_apps
                        else -> R.string.panel_recorder_preview_unavailable
                    }),fontSize=14.sp,color=panelMuted)
                }
            }
        }
        item {
            PanelCard {
                PanelSection(tr(context,R.string.gcam_video_storage))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PanelChoice("USB",config.storage==RecordingStorage.EXTERNAL,editable,Modifier.weight(1f)) {
                        RecorderSettings.saveStorage(context,RecordingStorage.EXTERNAL)
                        change(config.copy(storage=RecordingStorage.EXTERNAL)); storageRevision++
                    }
                    PanelChoice(tr(context,R.string.gcam_internal),config.storage==RecordingStorage.INTERNAL,editable,Modifier.weight(1f)) {
                        RecorderSettings.saveStorage(context,RecordingStorage.INTERNAL)
                        change(config.copy(storage=RecordingStorage.INTERNAL)); storageRevision++
                    }
                }
                if(config.storage==RecordingStorage.EXTERNAL) {
                    val selected=RecorderSettings.externalDriveId(context)
                    drives.forEach { drive ->
                        PanelChoice(drive.name,selected.equals(drive.id,true),editable,
                            Modifier.fillMaxWidth().padding(top=8.dp),drive.id) {
                            RecorderSettings.saveExternalDriveId(context,drive.id); storageRevision++; error=null
                        }
                    }
                    if(drives.isEmpty()) Text(tr(context,R.string.gcam_no_connected_usb_drives_found),
                        color=panelMuted,fontSize=13.sp,modifier=Modifier.padding(top=10.dp))
                    storageError?.let { Text(GCamLanguage.display(context,it),color=panelWarning,fontSize=13.sp,
                        modifier=Modifier.padding(top=8.dp)) }
                    TextButton(onClick={ storageRevision++ },enabled=editable) {
                        Text(tr(context,R.string.gcam_refresh_usb_drives),color=panelAccent)
                    }
                } else Text(tr(context,R.string.gcam_long_recordings_wear_out_internal_storage_usb_is_recommended),
                    color=panelMuted,fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
                PanelLabel(tr(context,R.string.gcam_archive_limit_1_s_gb_2_s,config.storageLimitGb,""))
                val limit=ArchiveRetention.sliderMaxGb(config.storageLimitGb,RecordingStore.maxArchiveGb(storage)).coerceAtLeast(1)
                PanelNumber(config.storageLimitGb,1,limit,editable,{ config=config.copy(storageLimitGb=it) }) {
                    RecorderSettings.save(context,config)
                }
                if(storage.freeBytes>=0) Text(tr(context,R.string.panel_recorder_free_space,formatBytes(context,storage.freeBytes)),
                    color=panelMuted,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
            }
        }
        item {
            PanelCard {
                PanelSection(tr(context,R.string.gcam_video_overlays))
                PanelToggle(tr(context,R.string.gcam_date_and_time),config.stampTime,editable) { change(config.copy(stampTime=it)) }
                PanelToggle(tr(context,R.string.gcam_gps_coordinates),config.stampGps,editable,
                    tr(context,R.string.gcam_shown_only_when_a_recent_location_is_available)) {
                    change(config.copy(stampGps=it))
                    if(it) (context as? MainActivity)?.requestEmbeddedPermissions()
                }
                PanelToggle(tr(context,R.string.panel_recorder_vehicle_speed),config.stampSpeed,editable,
                    tr(context,R.string.panel_recorder_vehicle_speed_hint)) { change(config.copy(stampSpeed=it)) }
                PanelToggle(tr(context,R.string.panel_recorder_auto_record),runtime.recordingAutostart,true,
                    tr(context,R.string.panel_recorder_auto_record_hint)) {
                    runtimeChange(runtime.copy(autostart=true,recordingAutostart=it))
                }
            }
        }
        item {
            PanelCard {
                TextButton(onClick={ extrasOpen=!extrasOpen },modifier=Modifier.fillMaxWidth()) {
                    Text(tr(context,R.string.panel_recorder_more),color=panelAccent)
                }
                if(extrasOpen) {
                    PanelToggle(tr(context,R.string.gcam_above_other_apps),runtime.cameraOverlay,true) {
                        runtimeChange(runtime.copy(cameraOverlay=it))
                        if(it && !Settings.canDrawOverlays(context)) openOverlay(context)
                        else GCamRuntimeController.applyCameraOverlaySetting(context,it)
                    }
                    PanelToggle(tr(context,R.string.gcam_floating_record_button),runtime.recordOverlay,true) {
                        runtimeChange(runtime.copy(recordOverlay=it))
                        if(it && !Settings.canDrawOverlays(context)) openOverlay(context)
                        else GCamRuntimeController.applyRecordOverlaySetting(context,it)
                    }
                    if(runtime.recordOverlay) {
                        PanelToggle(tr(context,R.string.gcam_monochrome_record_button),
                            runtime.recordOverlayMonochrome,true) {
                            runtimeChange(runtime.copy(recordOverlayMonochrome=it))
                            GCamRuntimeController.updateRecordOverlay(context)
                        }
                        PanelLabel(tr(context,R.string.gcam_record_button_size_1_s_dp,runtime.recordOverlaySizeDp))
                        PanelNumber(runtime.recordOverlaySizeDp,GCamRuntimeSettings.MIN_RECORD_OVERLAY_SIZE_DP,
                            GCamRuntimeSettings.MAX_RECORD_OVERLAY_SIZE_DP,true,
                            { runtime=runtime.copy(recordOverlaySizeDp=it) }) {
                            GCamRuntimeSettings.save(context,runtime)
                            GCamRuntimeController.updateRecordOverlay(context)
                        }
                    }
                }
            }
        }
        if(state.cameras.isNotEmpty()) item {
            PanelCard {
                TextButton(onClick={ diagnosticsOpen=!diagnosticsOpen },modifier=Modifier.fillMaxWidth()) {
                    Text(tr(context,R.string.panel_recorder_details),color=panelAccent)
                }
                if(diagnosticsOpen) state.cameras.values.sortedBy { it.inputId }.forEach { camera ->
                    val name=recorderCameraNames[camera.inputId]?.let { GCamLanguage.display(context,it) }.orEmpty()
                    val mode=when(camera.zeroCopy) {true->"ZERO-COPY";false->"PBO fallback";null->tr(context,R.string.gcam_checking)}
                    Text(name+" · "+mode+" · "+String.format(Locale.US,"%.1f",camera.fps)+" FPS",
                        fontSize=13.sp,color=panelMuted,modifier=Modifier.padding(vertical=4.dp))
                    camera.error?.let { Text(EmbeddedRecorderAccess.displayMessage(context,it),fontSize=13.sp,color=panelWarning) }
                }
            }
        }
    }
}

@Composable
internal fun EmbeddedArchivePermissionsCard() {
    val context=LocalContext.current
    val config=remember { RecorderSettings.load(context) }
    var state by remember { mutableStateOf(EmbeddedRecorderAccess.snapshot(context,config)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state=EmbeddedRecorderAccess.snapshot(context,config) }
    LaunchedEffect(Unit) { while(true) { state=EmbeddedRecorderAccess.snapshot(context,config); delay(1_500) } }
    EmbeddedPermissionCard(state) { state=EmbeddedRecorderAccess.snapshot(context,config) }
}

@Composable
private fun EmbeddedPermissionCard(state:EmbeddedRecorderAccess.State,refresh:()->Unit) {
    val context=LocalContext.current
    var failure by remember { mutableStateOf(false) }
    PanelCard {
        PanelSection(tr(context,R.string.panel_recorder_permissions))
        state.items.forEach { permission ->
            Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(permission.title,color=panelInk,fontSize=14.sp)
                    Text(if(permission.granted) tr(context,R.string.panel_recorder_allowed) else permission.detail,
                        color=if(permission.granted) panelMuted else panelWarning,fontSize=12.sp,
                        modifier=Modifier.padding(top=3.dp))
                }
                if(permission.granted) Text("✓",color=panelAccent,fontSize=20.sp)
                else TextButton(onClick={
                    failure=when(permission.action) {
                        EmbeddedRecorderAccess.Action.RUNTIME -> { (context as? MainActivity)?.requestEmbeddedPermissions(); false }
                        EmbeddedRecorderAccess.Action.ARCHIVE -> !EmbeddedRecorderAccess.openArchiveSettings(context)
                        EmbeddedRecorderAccess.Action.APP_SETTINGS -> !EmbeddedRecorderAccess.openAppSettings(context)
                        EmbeddedRecorderAccess.Action.OVERLAY -> !EmbeddedRecorderAccess.openOverlaySettings(context)
                    }
                    refresh()
                }) { Text(tr(context,R.string.panel_recorder_allow),color=panelAccent,fontSize=13.sp) }
            }
        }
        if(failure) Text(tr(context,R.string.panel_recorder_permissions_unavailable),
            color=panelWarning,fontSize=13.sp)
    }
}

@Composable
private fun PanelCard(color:Color=panelCard,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),color=color,shape=panelShape) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}
@Composable private fun PanelSection(title:String) {
    Text(title,color=panelInk,fontSize=18.sp,fontWeight=FontWeight.SemiBold,
        modifier=Modifier.padding(bottom=7.dp))
}
@Composable private fun PanelLabel(title:String) {
    Text(title,color=panelMuted,fontSize=14.sp,modifier=Modifier.padding(top=12.dp,bottom=6.dp))
}
@Composable
private fun PanelChoice(title:String,selected:Boolean,enabled:Boolean,modifier:Modifier=Modifier,
    subtitle:String?=null,action:()->Unit) {
    Surface(modifier.heightIn(min=46.dp).clickable(enabled=enabled,onClick=action),shape=RoundedCornerShape(10.dp),
        color=if(selected)panelSelected else Color(0xFF172D3E),
        border=if(selected)BorderStroke(1.dp,panelAccent.copy(alpha=.7f))else null) {
        Column(Modifier.padding(horizontal=10.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            Text(title,color=if(selected)panelAccent else if(enabled)panelInk else panelMuted,fontSize=15.sp,
                fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)
            subtitle?.let { Text(it,color=panelMuted,fontSize=11.sp,modifier=Modifier.padding(top=4.dp)) }
        }
    }
}
@Composable
private fun PanelChoices(options:List<Pair<Int,String>>,selected:Int,enabled:Boolean,choose:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        options.forEach { (id,label) -> PanelChoice(label,id==selected,enabled,Modifier.weight(1f)) { choose(id) } }
    }
}
@Composable
private fun PanelToggle(title:String,checked:Boolean,enabled:Boolean,subtitle:String?=null,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled) { change(!checked) }.padding(vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title,color=panelInk,fontSize=15.sp)
            subtitle?.let { Text(it,color=panelMuted,fontSize=12.sp,modifier=Modifier.padding(top=4.dp)) }
        }
        Switch(checked=checked,onCheckedChange=change,enabled=enabled,
            colors=SwitchDefaults.colors(checkedThumbColor=panelAccent,checkedTrackColor=panelSelected))
    }
}
@Composable
private fun PanelNumber(value:Int,min:Int,max:Int,enabled:Boolean,change:(Int)->Unit,finish:()->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick={change((value-1).coerceAtLeast(min));finish()},enabled=enabled && value>min,
            shape=RoundedCornerShape(10.dp),
            colors=ButtonDefaults.outlinedButtonColors(contentColor=panelAccent)) { Text("−",fontSize=20.sp) }
        Text(value.toString(),color=panelInk,fontSize=20.sp,fontWeight=FontWeight.SemiBold,
            modifier=Modifier.weight(1f))
        OutlinedButton(onClick={change((value+1).coerceAtMost(max));finish()},enabled=enabled && value<max,
            shape=RoundedCornerShape(10.dp),
            colors=ButtonDefaults.outlinedButtonColors(contentColor=panelAccent)) { Text("+",fontSize=20.sp) }
    }
    if(max>min) Slider(value.toFloat().coerceIn(min.toFloat(),max.toFloat()),
        onValueChange={change(it.roundToInt())},onValueChangeFinished=finish,
        valueRange=min.toFloat()..max.toFloat(),enabled=enabled,
        colors=SliderDefaults.colors(thumbColor=panelAccent,activeTrackColor=panelAccent,
            inactiveTrackColor=Color(0xFF29475D)))
}
private fun openOverlay(context:Context) {
    EmbeddedRecorderAccess.openOverlaySettings(context)
}
private fun tr(context:Context,id:Int,vararg args:Any?)=GCamLanguage.string(context,id,*args)
