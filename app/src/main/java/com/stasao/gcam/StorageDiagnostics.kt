package com.stasao.gcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Event-driven snapshots; no continuous logcat process and no writes to the failing USB. */
internal object StorageDiagnostics {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "gcam-storage-diag").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    @Volatile private var lastSnapshotMs = -60_000L
    private var rootAvailable: Boolean? = null
    private val kernelStorage = Regex(
        "usb|xhci|ehci|scsi|sd[a-z]|vold|exfat|vfat|fat-fs|fuse|I/O error|blk_update|Buffer I/O",
        RegexOption.IGNORE_CASE)

    fun observe(context: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                RecorderDiagnostics.recordStorage(context,
                    "storage_event action=${intent.action} path=${intent.data?.path}")
                capture(context, "event:${intent.action}")
            }
        }
        val media = IntentFilter().apply {
            listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED,
                Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_CHECKING,
                Intent.ACTION_MEDIA_UNMOUNTABLE).forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, media, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        return receiver
    }

    fun capture(context: Context, reason: String) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        // Four failing muxers describe one incident. Do not queue four shell dumps.
        if (now - lastSnapshotMs < 30_000 || !busy.compareAndSet(false, true)) return
        lastSnapshotMs = now
        worker.execute {
            try {
                snapshot(app, reason)
            } catch (t: Throwable) {
                RecorderDiagnostics.recordStorage(app,
                    "snapshot_failed type=${t.javaClass.simpleName} message=${t.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun snapshot(context: Context, reason: String) {
        fun log(text: String) = RecorderDiagnostics.recordStorage(context, text)
        log("snapshot reason=$reason selected=${RecorderSettings.externalDriveId(context)} " +
            "allFilesAccess=${ArchiveAccess.isGranted()}")
        if (rootAvailable == null) {
            val probe = runCatching { DiagnosticCommand.run(listOf("su", "-c", "id")) }.getOrNull()
            rootAvailable = probe?.exitCode == 0 && probe.output.contains("uid=0(")
            log("rootProbe available=$rootAvailable timedOut=${probe?.timedOut}")
        }
        log("systemLogAccess=${if (rootAvailable == true) "root" else "limited_app_permissions"}")
        fun collect(label: String, command: List<String>, filter: Boolean = false) {
            val args = if (rootAvailable == true) listOf("su", "-c",
                command.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }) else command
            // All commands/arguments are constants; no filenames or user text reach the shell.
            val result = runCatching { DiagnosticCommand.run(args) }
            result.onSuccess {
                val output = if (filter && it.exitCode == 0) it.output.lineSequence().filter { line ->
                    kernelStorage.containsMatchIn(line)
                }.joinToString("\n") else it.output
                log("$label exit=${it.exitCode} timedOut=${it.timedOut} " +
                    "matched=${output.isNotBlank()}\n$output")
            }.onFailure { log("$label unavailable=${it.javaClass.simpleName}:${it.message}") }
        }
        collect("storage_logcat", listOf("logcat", "-d", "-v", "threadtime", "-t", "800",
            "-b", "main", "-b", "system", "Vold:V", "vold:V", "StorageManagerService:V",
            "UsbHostManager:V", "UsbDeviceManager:V", "MediaProvider:W", "MPEG4Writer:W",
            "CCodec:W", "ACodec:W", "MediaCodec:W", "*:S"))
        collect("storage_kernel", listOf("dmesg"), filter = true)
        collect("storage_mounts", listOf("cat", "/proc/mounts"), filter = true)
        // Read buffered system messages first: vendor storage Binder calls can stall on bad USB.
        runCatching {
            val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
            volumes.forEach { volume ->
                log("volume id=${volume.uuid} state=${volume.state} removable=${volume.isRemovable} " +
                    "primary=${volume.isPrimary} path=${volume.directory} media=${volume.mediaStoreVolumeName}")
            }
            log("mediaVolumes=${MediaStore.getExternalVolumeNames(context)}")
            val drive = RecordingStore.selectedDrive(context)
            val path = drive?.volume?.directory?.absolutePath ?: drive?.id?.let { "/storage/$it" }
            val stat = path?.let { runCatching { StatFs(it) }.getOrNull() }
            log("selected_volume id=${drive?.id} path=$path totalBytes=${stat?.totalBytes ?: -1} " +
                "availableBytes=${stat?.availableBytes ?: -1}")
        }.onFailure { log("volume_snapshot_unavailable error=$it") }
        log("snapshot_complete")
    }
}
