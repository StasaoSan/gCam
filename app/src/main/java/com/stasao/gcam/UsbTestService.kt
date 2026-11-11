package com.stasao.gcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Starts on media mount even when the recorder itself is stopped. */
class UsbTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var newestStartId = 0
    private var queuedIntent: Intent? = null
    private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        newestStartId = startId
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            GCamLanguage.text(this, "Проверка USB", "USB test"), NotificationManager.IMPORTANCE_LOW))
        fun notice(text: String) = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("gCam · USB")
            .setContentText(text).setOngoing(true).build()
        startForeground(ID, notice(GCamLanguage.text(this, "Подготовка теста USB", "Preparing USB test")),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        intent?.getStringExtra("drive")?.let { id ->
            val old = RecorderSettings.externalDriveId(this)
            if (old != null && !old.equals(id, true)) UsbConnectionTest.invalidate(old)
            RecorderSettings.saveExternalDriveId(this, id)
        }
        if (job?.isActive == true) { queuedIntent = intent; return START_NOT_STICKY }
        val config = RecorderSettings.load(this).copy(
            cameraIds = intent?.getIntArrayExtra("cameras")?.toSet() ?: RecorderSettings.load(this).cameraIds,
            bitrateMbps = intent?.getIntExtra("bitrate", 2) ?: 2)
        job = scope.launch {
            try {
                // The recorder performs the same test itself before opening encoders.
                if (UsbConnectionTest.recorderBusy) return@launch
                var ready = false
                repeat(6) {
                    if (!ready) {
                        ready = runCatching { RecordingStore.validateExternal(this@UsbTestService) == null }
                            .getOrDefault(false)
                        if (!ready) delay(2_000)
                    }
                }
                if (ready && !UsbConnectionTest.recorderBusy) {
                    UsbConnectionTest.ensure(this@UsbTestService, config, onlyWhenIdle = true) { text ->
                        manager.notify(ID, notice(text))
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException)
                    RecorderDiagnostics.record(this@UsbTestService, "usb_connection_test_error error=$t")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    val queued = queuedIntent
                    queuedIntent = null
                    if (!destroyed && queued != null) {
                        job = null
                        onStartCommand(queued, 0, newestStartId)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(newestStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { destroyed = true; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "usb-test"
        private const val ID = 3605
        fun request(context: Context) {
            val config = RecorderSettings.load(context)
            if (config.storage != RecordingStorage.EXTERNAL) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, UsbTestService::class.java)
                    .putExtra("drive", RecorderSettings.externalDriveId(context))
                    .putExtra("cameras", config.cameraIds.toIntArray()).putExtra("bitrate", config.bitrateMbps))
            }.onFailure {
                RecorderDiagnostics.record(context, "usb_test_launch_deferred error=$it")
            }
        }
    }
}

class UsbTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED,
                Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT)) return
        val id = intent.data?.lastPathSegment ?: return
        UsbConnectionTest.invalidate(id)
        val selected = RecorderSettings.externalDriveId(context)
        if (intent.action == Intent.ACTION_MEDIA_MOUNTED && (selected == null || selected.equals(id, true)))
            UsbTestService.request(context)
    }
}
