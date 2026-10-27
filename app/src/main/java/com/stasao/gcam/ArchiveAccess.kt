package com.stasao.gcam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/** One-time special access for unattended retention of recordings from older installs. */
internal object ArchiveAccess {
    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    fun openSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in intents) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return true
        }
        RecorderDiagnostics.record(context, "archive_access_settings_unavailable package=${context.packageName}")
        return false
    }
}
