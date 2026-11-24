package com.stasao.gcam

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import java.io.File

/** Android access belongs to gPanel; vehicle camera capture does not use Android CAMERA. */
object EmbeddedRecorderAccess {
    enum class Action { RUNTIME, ARCHIVE, APP_SETTINGS, OVERLAY }
    data class Item(val key:String, val title:String, val granted:Boolean, val required:Boolean,
                    val action:Action, val detail:String)
    data class State(val items:List<Item>, val blocker:String?, val previewAvailable:Boolean)
    private const val REQUESTS="embedded_recorder_permissions"

    fun snapshot(context:Context,config:RecorderConfig):State {
        fun text(id:Int)=GCamLanguage.string(context,id)
        fun granted(permission:String)=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
        fun item(key:String,title:Int,permission:String,detail:Int)=Item(key,text(title),granted(permission),false,
            runtimeAction(context,permission),text(detail))
        val media=if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        val items=buildList {
            add(item("media",R.string.panel_recorder_access_media,media,R.string.panel_recorder_access_media_detail))
            val notificationPermission=Build.VERSION.SDK_INT<33 || granted(Manifest.permission.POST_NOTIFICATIONS)
            val notificationManager=context.getSystemService(NotificationManager::class.java)
            val recordingChannel=notificationManager?.getNotificationChannel("dashcam_recording")
            val notificationsEnabled=NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                recordingChannel?.importance!=NotificationManager.IMPORTANCE_NONE
            add(Item("notifications",text(R.string.panel_recorder_access_notifications),
                notificationPermission && notificationsEnabled,false,
                if(!notificationPermission)runtimeAction(context,Manifest.permission.POST_NOTIFICATIONS) else Action.APP_SETTINGS,
                text(R.string.panel_recorder_access_notifications_detail)))
            if(config.stampGps) add(item("gps",R.string.panel_recorder_access_gps,
                Manifest.permission.ACCESS_FINE_LOCATION,R.string.panel_recorder_access_gps_detail))
            add(Item("archive",text(R.string.panel_recorder_access_archive),runCatching { ArchiveAccess.isGranted() }.getOrDefault(false),false,
                Action.ARCHIVE,text(R.string.panel_recorder_access_archive_detail)))
            val floating=GCamRuntimeSettings.load(context)
            if(floating.cameraOverlay || floating.recordOverlay) {
                add(Item("overlay",text(R.string.panel_recorder_access_overlay),Settings.canDrawOverlays(context),false,
                    Action.OVERLAY,text(R.string.panel_recorder_access_overlay_detail)))
            }
        }
        val previewAvailable=vehicleCamerasAvailable()
        val blocker=when {
            config.cameraIds.isEmpty()->text(R.string.panel_recorder_access_choose_camera)
            !RecorderHost.captureAvailable(context)->text(R.string.panel_recorder_access_camera_owner)
            !previewAvailable->text(R.string.panel_recorder_access_vehicle_camera)
            config.storage==RecordingStorage.EXTERNAL->RecordingStore.validateExternal(context)?.let { GCamLanguage.display(context,it) }
            else->null
        }
        return State(items,blocker,previewAvailable)
    }

    /** Missing access only. Android 12 requires requesting coarse and fine together. */
    fun runtimePermissions(context:Context,config:RecorderConfig):Array<String> {
        fun missing(permission:String)=context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED
        return buildList {
            val media=if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            if(missing(media)) add(media)
            if(Build.VERSION.SDK_INT>=33 && missing(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if(config.stampGps && missing(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.toTypedArray()
    }

    /** Automatic requests happen once; a denied permission remains available through its button. */
    fun initialRuntimePermissions(context:Context,config:RecorderConfig):Array<String> {
        val prefs=context.getSharedPreferences(REQUESTS,Context.MODE_PRIVATE)
        val missing=runtimePermissions(context,config)
        val initial=missing.filterNot { prefs.getBoolean(it,false) }.toMutableSet()
        if(Manifest.permission.ACCESS_FINE_LOCATION in missing &&
            (Manifest.permission.ACCESS_FINE_LOCATION in initial || Manifest.permission.ACCESS_COARSE_LOCATION in initial)) {
            initial+=Manifest.permission.ACCESS_FINE_LOCATION
            initial+=Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return initial.toTypedArray()
    }

    fun markRuntimeRequested(context:Context,permissions:Array<String>) {
        context.getSharedPreferences(REQUESTS,Context.MODE_PRIVATE).edit().apply {
            permissions.forEach { putBoolean(it,true) }
        }.apply()
    }

    private fun runtimeAction(context:Context,permission:String):Action {
        val requested=context.getSharedPreferences(REQUESTS,Context.MODE_PRIVATE).getBoolean(permission,false)
        var base=context
        val seen=mutableSetOf<Context>()
        while(base !is Activity && base is ContextWrapper && seen.add(base)) base=base.baseContext
        return if(requested && base is Activity && !base.shouldShowRequestPermissionRationale(permission))
            Action.APP_SETTINGS else Action.RUNTIME
    }

    fun openArchiveSettings(context:Context):Boolean = launch(context,listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ))

    fun openAppSettings(context:Context):Boolean = launch(context,listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")),
    ))

    fun openOverlaySettings(context:Context):Boolean = launch(context,listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    ))

    /** The shared archive exception keeps its standalone wording; embedded UI names its own controls. */
    fun displayMessage(context:Context,text:String):String {
        val current=if(RecorderHost.embedded(context)) text.replace(ArchiveAccessRequiredException.MESSAGE,
            GCamLanguage.string(context,R.string.panel_recorder_archive_delete_denied)) else text
        return GCamLanguage.display(context,current)
    }

    fun resumeFloatingFeatures(context:Context) {
        if(!RecorderHost.embedded(context) || !Settings.canDrawOverlays(context)) return
        val floating=GCamRuntimeSettings.load(context)
        if(floating.cameraOverlay) GCamRuntimeController.applyCameraOverlaySetting(context,true)
        if(floating.recordOverlay) GCamRuntimeController.applyRecordOverlaySetting(context,true)
    }

    private fun launch(context:Context,intents:List<Intent>):Boolean {
        for(intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                RecorderDiagnostics.record(context,"embedded_permission_settings_open action=${intent.action} package=${context.packageName}")
                return true
            }
        }
        RecorderDiagnostics.record(context,"embedded_permission_settings_unavailable package=${context.packageName}")
        return false
    }

    private fun vehicleCamerasAvailable():Boolean = Build.MODEL.equals("G636",ignoreCase=true) ||
        listOf("/system/lib64/libais_hidl_client.so","/vendor/lib64/libais_hidl_client.so",
            "/odm/lib64/libais_hidl_client.so").any { File(it).isFile }
}
