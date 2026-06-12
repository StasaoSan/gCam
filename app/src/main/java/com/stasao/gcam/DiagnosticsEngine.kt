package com.stasao.gcam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticsEngine(internal val context: Context) {

    suspend fun runAll(onProgress: (CheckResult) -> Unit) = withContext(Dispatchers.IO) {
        // Crash recovery: пишем каждую проверку сразу в файл.
        // Если приложение упадёт в HIDL-проверке — все предыдущие результаты уже на диске.
        val crashFile = try {
            java.io.File(context.getExternalFilesDir(null), "gcam_last_run.txt").also { f ->
                f.writeText("=== gCam Diagnostics (crash recovery) ===\n" +
                    "Started: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n\n")
            }
        } catch (_: Exception) { null }

        suspend fun report(r: CheckResult) {
            withContext(Dispatchers.Main) { onProgress(r) }
            try { crashFile?.appendText("[${r.status.name}] ${r.title}\n${r.detail.trimEnd()}\n\n") }
            catch (_: Exception) {}
        }

        // Лёгкие проверки — идут первыми, чтобы данные точно сохранились
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
        // QCarCam HIDL — самая долгая и потенциально крашащая — идёт последней
        report(checkQCarCamHidl())

        try { crashFile?.appendText("=== RUN COMPLETED ===\n") } catch (_: Exception) {}
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
