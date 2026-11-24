package com.stasao.gcam

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small, persistent recorder journal. Never write from the frame/codec hot path. */
object RecorderDiagnostics {
    private const val TAG = "GCAM_DIAG"
    private const val MAX_BYTES = 4 * 1024 * 1024L
    private val lock = Any()

    private fun directory(context: Context) = File(context.filesDir, "diagnostics")

    fun environment(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return "package=${context.packageName} version=${info?.versionName} versionCode=${info?.longVersionCode} " +
            "manufacturer=${android.os.Build.MANUFACTURER} model=${android.os.Build.MODEL} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} diagnosticSchema=3 recorderRecovery=isolated-codec-v1"
    }

    fun record(context: Context, event: String) = append(context, "recorder", event)

    fun recordException(context: Context, event: String, failure: Throwable) = record(
        context,
        "$event stack=${Log.getStackTraceString(failure).replace("\n", "\\n")}")

    /** The camera and overlay processes use separate files to avoid cross-process append races. */
    fun recordHud(context: Context, event: String) = append(context, "hud", event)

    fun recordStatus(context: Context, event: String) = append(context, "status", event)

    fun recordStorage(context: Context, event: String) = append(context, "storage-system", event)

    private fun append(context: Context, source: String, event: String) {
        Log.i(TAG, event)
        runCatching {
            journalLock(context) {
                val dir = directory(context).apply { mkdirs() }
                val current = File(dir, "$source.log")
                val limit = if (source == "storage-system") 1024 * 1024L else MAX_BYTES
                val embedded = RecorderHost.embedded(context)
                val message = if (embedded) event.take(16_000) else event
                if (current.length() + message.toByteArray(Charsets.UTF_8).size + (if (embedded) 256 else 0) >= limit) {
                    val previous = File(dir, "$source-previous.log")
                    if (previous.exists()) previous.delete()
                    current.renameTo(previous)
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val process = if (embedded) " pid=${android.os.Process.myPid()} process=${android.app.Application.getProcessName()}" else ""
                current.appendText("$stamp elapsedMs=${SystemClock.elapsedRealtime()}$process $message\n")
            }
        }.onFailure { Log.w(TAG, "Journal write failed", it) }
    }

    /** Both :recorder and :qcarcam write these journals; snapshot and rotation share one file lock. */
    private fun <T> journalLock(context: Context, action: () -> T): T = synchronized(lock) {
        if (!RecorderHost.embedded(context)) return@synchronized action()
        val dir = directory(context).apply { mkdirs() }
        RandomAccessFile(File(dir, ".panel-journal.lock"), "rw").channel.use { channel ->
            channel.lock().use { action() }
        }
    }

    /**
     * Fixed app-private allowlist. The archive owns cancellation before capture starts;
     * neither compression nor the destination ever holds either journal writer lock.
     */
    fun appendToPanelReport(
        context: Context,
        zip: ZipOutputStream,
        checkActive: () -> Unit = {},
        registerCloseable: (Closeable) -> Unit = {},
        openInput: (File) -> FileInputStream = { it.inputStream() }
    ) {
        if (!RecorderHost.embedded(context)) return
        ReportSnapshot().use { snapshot ->
            registerCloseable(snapshot)
            checkActive()
            journalLock(context) {
                checkActive()
                val dir = directory(context)
                val parent = dir.canonicalFile
                listOf("recorder", "hud", "status", "storage-system").forEach { source ->
                    for (suffix in listOf("-previous.log", ".log")) {
                        checkActive()
                        val file = File(dir, "$source$suffix")
                        if (!file.isFile || file.canonicalFile.parentFile != parent) continue
                        val limit = if (source == "storage-system") 1024 * 1024L else MAX_BYTES
                        val group = if (source == "hud" || source == "status") "cameras" else "recorder"
                        snapshot.capture(file, "$group/${file.name}", limit, openInput, checkActive)
                    }
                }
            }
            checkActive()
            snapshot.appendTo(zip, checkActive)
        }
    }

    /** Each descriptor pins its inode; only a fixed tail range is copied after capture. */
    private class ReportSnapshot : Closeable {
        private class Source(val name: String, val input: FileInputStream, var length: Long = 0)
        private val lock = Any()
        private val sources = ArrayList<Source>(8)
        private var closed = false

        fun capture(file: File, name: String, limit: Long, openInput: (File) -> FileInputStream, active: () -> Unit) {
            val source = Source(name, openInput(file))
            val accepted = synchronized(lock) {
                if (closed) false else { sources.add(source); true }
            }
            if (!accepted) {
                val failure = InterruptedIOException("Recorder log export cancelled")
                try { source.input.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            // Own the newly opened descriptor before size/seek, including their failure paths.
            active()
            val channel = source.input.channel
            val size = channel.size()
            channel.position((size - limit).coerceAtLeast(0))
            source.length = minOf(size, limit)
            active()
        }

        fun appendTo(zip: ZipOutputStream, active: () -> Unit) {
            val entries = synchronized(lock) {
                if (closed) throw InterruptedIOException("Recorder log export cancelled")
                sources.toList()
            }
            val buffer = ByteArray(16_384)
            entries.forEach { source ->
                active(); zip.putNextEntry(ZipEntry(source.name))
                var left = source.length
                while (left > 0) {
                    active()
                    val count = source.input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (count < 0) throw EOFException("Recorder journal snapshot was truncated")
                    if (count == 0) continue
                    active(); zip.write(buffer, 0, count); left -= count
                }
                active(); zip.closeEntry()
            }
        }

        override fun close() {
            val entries = synchronized(lock) {
                if (closed) return
                closed = true
                sources.toList().also { sources.clear() }
            }
            var failure: Throwable? = null
            entries.forEach { source ->
                try { source.input.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    fun share(context: Context) {
        if (RecorderHost.embedded(context)) {
            val intent = Intent("${context.packageName}.SHARE_REPORT")
                .setClassName(context.packageName, "${context.packageName}.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            (context as? android.app.Activity)?.overridePendingTransition(0, 0)
            return
        }
        val export = synchronized(lock) {
            val dir = directory(context)
            val outDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(outDir, "gCam-recorder-diagnostics.txt")
            file.outputStream().buffered().use { output ->
                output.write(("# Export environment (historical sessions may use older builds)\n" +
                    environment(context) + "\n").toByteArray(Charsets.UTF_8))
                listOf(
                    "recorder-previous.log", "recorder.log",
                    "hud-previous.log", "hud.log",
                    "status-previous.log", "status.log",
                    "storage-system-previous.log", "storage-system.log"
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
        val uri = FileProvider.getUriForFile(context, RecorderHost.fileAuthority(context), export)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent,
            GCamLanguage.string(context, R.string.gcam_share_gcam_diagnostics)))
    }
}
