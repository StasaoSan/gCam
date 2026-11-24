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
        if (RecorderHost.embedded(context)) return false
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
                "error=${result.exceptionOrNull()?.javaClass?.simpleName} " +
                "message=${result.exceptionOrNull()?.message?.take(512)} " +
                "output=${result.getOrNull()?.output?.replace("\n", " ")?.take(1024)}")
        recordState(context)
        return granted
    }

    /** Capture the platform decision so permission resets can be distinguished from UI state. */
    fun recordState(context: Context) {
        val granted = isGranted()
        val prefs = context.getSharedPreferences("archive_access_diagnostics", Context.MODE_PRIVATE)
        val previous = if (prefs.contains("granted")) prefs.getBoolean("granted", false).toString() else "unknown"
        val now = System.currentTimeMillis()
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        val mode = runCatching {
            context.getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
                "android:manage_external_storage",
                android.os.Process.myUid(), context.packageName)
        }.getOrNull()
        RecorderDiagnostics.record(context,
            "archive_access allFilesAccess=$granted previous=$previous package=${context.packageName} " +
                "uid=${android.os.Process.myUid()} boot=$boot appOpMode=$mode " +
                "lastGrantedAtMs=${prefs.getLong("last_granted_at", 0L)}")
        prefs.edit().putBoolean("granted", granted).apply {
            if (granted && previous != "true") putLong("last_granted_at", now)
        }.apply()
    }

    fun openSettings(context: Context): Boolean {
        if (RecorderHost.embedded(context)) return EmbeddedRecorderAccess.openArchiveSettings(context)
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
