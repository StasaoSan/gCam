package com.stasao.gcam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticsEngine(internal val context: Context) {

    suspend fun runAll(onProgress: (CheckResult) -> Unit) = withContext(Dispatchers.IO) {
        suspend fun report(r: CheckResult) = withContext(Dispatchers.Main) { onProgress(r) }

        report(checkDeviceInfo())
        report(checkSystemProps())
        report(checkProcessesViaProcfs())
        report(checkSeLinux())
        report(checkOwnGids())
        report(checkVideoDevices())
        report(checkV4L2NodeNames())
        report(checkCameraHal())
        report(checkShellServiceList())
        report(checkShellLsZSocket())
        report(checkCameraSocketDir())
        report(checkHidlCameraProvider())
        report(checkHwServiceManager())
        report(checkQCarCamProcess())
        report(checkQCarCamHidl())
        report(checkVendorSockets())
        report(checkVendorInitFiles())
        report(checkVendorCameraProps())
        report(checkCarPropertyCamera())
        report(checkCarVendorExtension())
        report(checkCarAndEvsDetail())
        report(checkInstalledPackages())
        report(checkECarXCarService())
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
