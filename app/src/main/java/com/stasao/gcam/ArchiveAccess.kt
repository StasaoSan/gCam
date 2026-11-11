package com.stasao.gcam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/** One-time special access for unattended retention of recordings from older installs. */
internal object ArchiveAccess {
    private var rootAttempted = false
    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /** Called off the UI thread. Ordinary devices keep the explicit Android permission flow. */
    @Synchronized fun tryGrantWithRoot(context: Context, retry: Boolean = false): Boolean {
        if (isGranted()) return true
        if (rootAttempted && !retry) return false
        rootAttempted = true
        val packageName = context.packageName
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        val result = runCatching {
            DiagnosticCommand.run(listOf("su", "-c",
                "appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow"),
                timeoutMs = 4_000, maxChars = 2_048)
        }
        val granted = isGranted()
        RecorderDiagnostics.record(context,
            "archive_access_auto granted=$granted package=$packageName " +
                "exitCode=${result.getOrNull()?.exitCode} timedOut=${result.getOrNull()?.timedOut} " +
                "error=${result.exceptionOrNull()?.javaClass?.simpleName}")
        return granted
    }

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
