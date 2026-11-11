package com.stasao.gcam

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Shared by both services in :recorder, so automatic testing and recorder preflight cannot overlap. */
internal object UsbConnectionTest {
    private val mutex = Mutex()
    private val epochs = ConcurrentHashMap<String, AtomicLong>()
    private data class Cached(val epoch: Long, val result: UsbStressTest.Result?, val error: String?)
    private val results = ConcurrentHashMap<String, Cached>()
    private val stateLock = Any()
    @Volatile var recorderBusy = false

    fun invalidate(id: String) { epochs.getOrPut(id.lowercase(Locale.ROOT)) { AtomicLong() }.incrementAndGet() }

    private fun stateFile(context: Context) = AtomicFile(File(context.filesDir, "diagnostics/usb-test-state.json"))

    fun state(context: Context): JSONObject? = runCatching {
        JSONObject(String(stateFile(context).readFully(), Charsets.UTF_8))
    }.getOrNull()

    fun publish(context: Context, id: String, ru: String, en: String, running: Boolean) = synchronized(stateLock) {
        runCatching {
            val file = stateFile(context)
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try {
                stream.write(JSONObject().put("drive", id).put("ru", ru).put("en", en)
                    .put("running", running).put("at", System.currentTimeMillis()).toString().toByteArray())
                file.finishWrite(stream)
            } catch (t: Throwable) { file.failWrite(stream); throw t }
        }
    }

    suspend fun ensure(context: Context, config: RecorderConfig, onlyWhenIdle: Boolean = false,
                       onProgress: (String) -> Unit = {}): UsbStressTest.Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (onlyWhenIdle && recorderBusy) throw kotlinx.coroutines.CancellationException("Recorder owns USB")
            val coroutine = currentCoroutineContext()
            val id = requireNotNull(RecordingStore.selectedDrive(context)) { "USB not connected" }.id
                .lowercase(Locale.ROOT)
            val epoch = epochs.getOrPut(id) { AtomicLong() }.get()
            val cached = results[id]?.takeIf { it.epoch == epoch }
            if (cached != null) {
                cached.error?.let { throw UsbTestFailure(it) }
                return@withLock requireNotNull(cached.result)
            }
            val started = SystemClock.elapsedRealtime()
            val deadline = CoroutineScope(coroutine).launch(Dispatchers.Default) {
                delay(UsbStressTest.DEADLINE_MS)
                val ru = "Тест USB: превышено 60 с. Ожидаю завершения операции накопителя."
                val en = "USB test: 60 s exceeded. Waiting for the storage operation to finish."
                publish(context, id, ru, en, true)
                onProgress(GCamLanguage.text(context, ru, en))
            }
            var lastProgressAt = -1L
            val targets = mutableListOf<RecordingTarget>()
            val handles = mutableListOf<UsbStressTest.TestFile>()
            var failure: Throwable? = null
            var result: UsbStressTest.Result? = null
            fun checkConnected() {
                coroutine.ensureActive()
                if (epochs[id]?.get() != epoch) throw UsbTestFailure("USB disconnected during test")
                if (SystemClock.elapsedRealtime() - started >= UsbStressTest.DEADLINE_MS)
                    throw UsbTestFailure("USB test timeout (60 s)")
            }
            try {
                val destination = RecordingStore.usbTestCollection(context)
                RecordingStore.removeUsbTestOrphans(context, destination)
                val free = RecordingStore.storageStats(context, RecordingStorage.EXTERNAL).freeBytes
                if (free < UsbStressTest.TOTAL_BYTES + 32_000_000)
                    throw UsbTestFailure("USB test needs at least 232 MB free")
                RecorderDiagnostics.record(context, "usb_test_start volume=$id bytes=${UsbStressTest.TOTAL_BYTES} deadlineMs=60000")
                repeat(4) { index ->
                    checkConnected()
                    val target = RecordingStore.createUsbTestTarget(context, destination,
                        "gcam-usb-test-${UUID.randomUUID()}-$index.mp4")
                    targets += target
                    handles += object : UsbStressTest.TestFile {
                        override val output = ParcelFileDescriptor.AutoCloseOutputStream(
                            ParcelFileDescriptor.dup(target.descriptor.fileDescriptor))
                        override fun sync() { output.flush(); target.descriptor.fileDescriptor.sync() }
                        override fun openInput() = requireNotNull(context.contentResolver.openInputStream(target.uri))
                        override fun close() { output.close() }
                    }
                }
                result = UsbStressTest.run(handles, checkConnected = ::checkConnected) { phase, percent ->
                    val now = SystemClock.elapsedRealtime()
                    if (lastProgressAt < 0 || now - lastProgressAt >= 1_000) {
                        val ru = "Тест USB: ${if (phase == "write") "запись" else "проверка чтением"} · $percent% · ${(now - started) / 1000}/60 с"
                        val en = "USB test: ${if (phase == "write") "writing" else "verifying"} · $percent% · ${(now - started) / 1000}/60 s"
                        publish(context, id, ru, en, true)
                        onProgress(GCamLanguage.text(context, ru, en))
                        lastProgressAt = now
                    }
                }
            } catch (t: Throwable) { failure = t }
            finally {
                handles.forEach { runCatching { it.close() }.onFailure { if (failure == null) failure = it } }
                targets.forEach { target ->
                    runCatching {
                        target.descriptor.close()
                        check(context.contentResolver.delete(target.uri, null, null) > 0) { "USB test cleanup failed" }
                    }.onFailure {
                        RecorderDiagnostics.record(context, "usb_test_cleanup_error uri=${target.uri} error=$it")
                        if (failure == null) failure = it
                    }
                }
                withContext(NonCancellable) { deadline.cancelAndJoin() }
            }
            if (failure == null && SystemClock.elapsedRealtime() - started >= UsbStressTest.DEADLINE_MS)
                failure = UsbTestFailure("USB test timeout (60 s)")
            failure?.let { error ->
                val message = error.message ?: error.javaClass.simpleName
                if (epochs[id]?.get() == epoch && error !is kotlinx.coroutines.CancellationException)
                    results[id] = Cached(epoch, null, message)
                publish(context, id, "Тест USB не пройден: $message. Переподключите накопитель.",
                    "USB test failed: $message. Reconnect the drive.", false)
                RecorderDiagnostics.recordException(context, "usb_test_failed volume=$id", error)
                StorageDiagnostics.capture(context, "usb_test:$message")
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw UsbTestFailure(message)
            }
            val completed = requireNotNull(result)
            results[id] = Cached(epoch, completed, null)
            val speeds = String.format(Locale.US, "%.1f / %.1f MB/s",
                completed.writeBytesPerSecond / 1_000_000, completed.readBytesPerSecond / 1_000_000)
            val fast = completed.hasHeadroom(config.cameraIds.size, config.bitrateMbps)
            publish(context, id,
                "Тест USB пройден · запись/чтение $speeds · пауза ${completed.maxOperationMs} мс" +
                    if (fast) "" else " · Скорость ниже рекомендуемой для выбранных настроек",
                "USB test passed · write/read $speeds · pause ${completed.maxOperationMs} ms" +
                    if (fast) "" else " · Speed below recommendation for selected settings", false)
            RecorderDiagnostics.record(context, "usb_test_passed volume=$id result=$completed headroom=$fast")
            completed
        }
    }
}
