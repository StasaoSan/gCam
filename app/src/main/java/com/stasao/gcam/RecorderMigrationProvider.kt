package com.stasao.gcam

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** An explicit handoff between releases signed by the same publisher. */
class RecorderMigrationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val caller = Binder.getCallingUid()
        val pm = context.packageManager
        if (pm.getPackagesForUid(caller)?.contains("com.stasao.gpanel") != true ||
            pm.checkSignatures(caller, context.applicationInfo.uid) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Untrusted recorder migration caller")
        }
        return when (method) {
            "settings" -> RecorderMigration.settings(context)
            "retire" -> {
                // This provider and DashcamService run in :recorder. Serialize the
                // idle check with startRecording and the final encoder release.
                val task = FutureTask {
                    if (RecorderRepository.busy) return@FutureTask false
                    val capture = ComponentName(context, QCarCamCaptureService::class.java)
                    pm.setComponentEnabledSetting(capture, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
                    true
                }
                Handler(Looper.getMainLooper()).post(task)
                val accepted = try {
                    task.get(2, TimeUnit.SECONDS)
                } finally {
                    task.cancel(false)
                }
                Bundle().apply { putBoolean("retired", accepted) }
            }
            else -> throw IllegalArgumentException("Unknown recorder migration method")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal object RecorderMigration {
    private val settingGroups = mapOf(
        "dashcam_settings" to setOf(
            "camera_0", "camera_1", "camera_2", "camera_3", "recording_width", "recording_fps",
            "bitrate_mbps", "segment_minutes", "storage_limit_gb", "storage",
            "stamp_time", "stamp_gps", "stamp_speed", "ui_scale"
        ),
        "gcam_runtime" to setOf(
            "autostart", "recording_autostart", "status_overlay", "blind_status_overlay", "hud_status_overlay",
            "camera_overlay", "record_overlay", "record_overlay_x", "record_overlay_y",
            "record_overlay_size_dp", "record_overlay_monochrome", "status_overlay_x", "status_overlay_y",
            "blind_status_overlay_x", "blind_status_overlay_y", "hud_status_overlay_x", "hud_status_overlay_y"
        )
    )

    fun settings(context: Context): Bundle = Bundle().apply {
        for ((group, keys) in settingGroups) {
            // UI settings are saved by the main process. Refresh any old
            // SharedPreferences instance retained by the recorder process.
            @Suppress("DEPRECATION")
            val prefs = context.getSharedPreferences(group, Context.MODE_MULTI_PROCESS)
            val values = Bundle()
            for ((key, value) in prefs.all) {
                if (key !in keys) continue
                when (value) {
                    is Boolean -> values.putBoolean(key, value)
                    is Int -> values.putInt(key, value)
                    is Float -> values.putFloat(key, value)
                    is String -> values.putString(key, value)
                }
            }
            putBundle(group, values)
        }
    }

    fun transfer(context: Context, packageName: String): Boolean {
        require(RecorderHost.embedded(context) && packageName in RecorderHost.legacyPackages)
        val uri = Uri.parse("content://$packageName.recorder.migration")
        // Android terminates the old app when its capture service is disabled.
        // An unstable client lets gPanel survive the provider process exiting.
        val provider = context.contentResolver.acquireUnstableContentProviderClient(uri) ?: return false
        provider.use { client ->
            val snapshot = client.call("settings", null, null) ?: return false
            copySettings(context, snapshot)
            runCatching { client.call("retire", null, null) }
        }
        val deadline = SystemClock.elapsedRealtime() + 2_000
        do {
            if (!RecorderHost.captureEnabled(context, packageName)) return true
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun copySettings(context: Context, snapshot: Bundle) {
        for ((group, keys) in settingGroups) {
            val values = snapshot.getBundle(group) ?: continue
            val editor = context.getSharedPreferences(group, Context.MODE_PRIVATE).edit()
            for (key in keys) {
                @Suppress("DEPRECATION")
                when (val value = values.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            check(editor.commit()) { "Не удалось сохранить настройки регистратора" }
        }
    }
}
