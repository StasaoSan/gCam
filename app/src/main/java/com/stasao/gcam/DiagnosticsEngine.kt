package com.stasao.gcam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticsEngine(internal val context: Context) {

    suspend fun runAll(onProgress: (CheckResult) -> Unit) = withContext(Dispatchers.IO) {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val header = "=== gCam Diagnostics ===\nStarted: $ts\n\n"

        // MediaStore: создаём запись ОДИН РАЗ, держим OutputStream открытым весь прогон.
        // flush() после каждой проверки — данные немедленно на диске даже при краше.
        val crashUri = try {
            context.contentResolver.delete(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf("gcam_last_run.txt"))
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "gcam_last_run.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        } catch (_: Exception) { null }

        val crashStream: java.io.OutputStream? = try {
            crashUri?.let { context.contentResolver.openOutputStream(it) }
                ?.also { os -> os.write(header.toByteArray()); os.flush() }
        } catch (_: Exception) { null }

        // Резервный файл в приватной папке (страховка)
        val crashFile: java.io.File? = try {
            java.io.File(context.getExternalFilesDir(null), "gcam_last_run.txt")
                .also { it.writeText(header) }
        } catch (_: Exception) { null }

        suspend fun report(r: CheckResult) {
            withContext(Dispatchers.Main) { onProgress(r) }
            val line = "[${r.status.name}] ${r.title}\n${r.detail.trimEnd()}\n\n"
            try { crashStream?.write(line.toByteArray()); crashStream?.flush() } catch (_: Exception) {}
            try { crashFile?.appendText(line) } catch (_: Exception) {}
        }

        report(checkDeviceInfo())
        report(checkSystemProps())
        report(checkSeLinux())
        report(checkOwnGids())
        report(checkShellServiceList())
        report(checkQCarCamProcess())
        report(checkVendorSockets())
        report(checkVendorInitFiles())
        report(checkVendorCameraProps())
        report(checkInstalledPackages())
        report(checkECarXCarService())
        report(checkQCarCamDeeper())
        report(checkECarXEvs())
        // QCarCam HIDL — самая долгая и потенциально крашащая — идёт последней
        val hidlResult = try { checkQCarCamHidl() } catch (t: Throwable) {
            CheckResult("QCarCam HIDL", CheckStatus.FAIL,
                "NATIVE CRASH: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
        }
        report(hidlResult)

        val done = "=== RUN COMPLETED ===\n"
        try { crashStream?.write(done.toByteArray()); crashStream?.flush(); crashStream?.close() } catch (_: Exception) {}
        try { crashFile?.appendText(done) } catch (_: Exception) {}
    }

    internal data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int)

    internal fun shell(vararg cmd: String, timeoutMs: Long = 4000): ShellResult {
        return try {
            val proc = Runtime.getRuntime().exec(cmd)
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return ShellResult("", "timeout after ${timeoutMs}ms", -1)
            }
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            ShellResult(stdout.trim(), stderr.trim(), proc.exitValue())
        } catch (e: Exception) {
            ShellResult("", e.message ?: "exec failed", -1)
        }
    }
}
