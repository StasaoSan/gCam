package com.stasao.gcam

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, persistent recorder journal. Never write from the frame/codec hot path. */
object RecorderDiagnostics {
    private const val TAG = "GCAM_DIAG"
    private const val MAX_BYTES = 1024 * 1024L
    private val lock = Any()

    private fun directory(context: Context) = File(context.filesDir, "diagnostics")

    fun record(context: Context, event: String) = append(context, "recorder", event)

    /** The camera and overlay processes use separate files to avoid cross-process append races. */
    fun recordHud(context: Context, event: String) = append(context, "hud", event)

    fun recordStatus(context: Context, event: String) = append(context, "status", event)

    private fun append(context: Context, source: String, event: String) {
        Log.i(TAG, event)
        runCatching {
            synchronized(lock) {
                val dir = directory(context).apply { mkdirs() }
                val current = File(dir, "$source.log")
                if (current.length() >= MAX_BYTES) {
                    val previous = File(dir, "$source-previous.log")
                    if (previous.exists()) previous.delete()
                    current.renameTo(previous)
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                current.appendText("$stamp elapsedMs=${SystemClock.elapsedRealtime()} $event\n")
            }
        }.onFailure { Log.w(TAG, "Journal write failed", it) }
    }

    fun share(context: Context) {
        val export = synchronized(lock) {
            val dir = directory(context)
            val outDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(outDir, "gCam-recorder-diagnostics.txt")
            file.outputStream().buffered().use { output ->
                listOf(
                    "recorder-previous.log", "recorder.log",
                    "hud-previous.log", "hud.log",
                    "status-previous.log", "status.log"
                ).forEach { name ->
                    val source = File(dir, name)
                    if (source.isFile) {
                        output.write("\n# $name\n".toByteArray(Charsets.UTF_8))
                        source.inputStream().buffered().use { it.copyTo(output) }
                    }
                }
            }
            file
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", export)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить диагностику gCam"))
    }
}
