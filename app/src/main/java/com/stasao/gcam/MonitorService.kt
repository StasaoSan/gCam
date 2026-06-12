package com.stasao.gcam

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@android.annotation.SuppressLint("NotificationPermission")
class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "gcam_monitor"
        const val NOTIF_ID = 42

        private val _lines = MutableStateFlow<List<String>>(emptyList())
        val lines: StateFlow<List<String>> = _lines

        private val buffer = ArrayDeque<String>()

        fun logFile(ctx: Context) = File(ctx.getExternalFilesDir(null), "gcam_monitor.log")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("Запущен, жду изменений..."))
        val lf = logFile(this)
        lf.parentFile?.mkdirs()
        lf.appendText("=== START ${Date()} ===\n")
        log("Monitor запущен")

        scope.launch {
            var prevSocket = ""
            var prevUnix = ""

            while (isActive) {
                val ts = fmt.format(Date())

                // 1. /dev/socket/camera/ contents
                val socketDir = File("/dev/socket/camera")
                val socketStatus = when {
                    !socketDir.exists() -> "не существует"
                    socketDir.isFile -> "ФАЙЛ"
                    socketDir.isDirectory -> {
                        val kids = try { socketDir.listFiles() } catch (_: Exception) { null }
                        when {
                            kids == null -> {
                                // Fallback: try shell ls — might work even without GID 1006
                                try {
                                    val proc = Runtime.getRuntime().exec(
                                        arrayOf("ls", "-la", "/dev/socket/camera/"))
                                    val out = proc.inputStream.bufferedReader().readText().trim()
                                    val err = proc.errorStream.bufferedReader().readText().trim()
                                    proc.waitFor()
                                    if (out.isNotEmpty()) "DIR(shell:${out.lines().filter { it.isNotBlank() && !it.startsWith("total") }.joinToString("|") { it.trim().split(" ").last() }})"
                                    else "DIR(нет доступа: $err)"
                                } catch (_: Exception) { "DIR(нет доступа)" }
                            }
                            kids.isEmpty() -> "DIR(пусто)"
                            else -> "DIR(${kids.size} файлов: ${kids.joinToString { it.name }})"
                        }
                    }
                    else -> "unknown"
                }

                // 2. /proc/net/unix for camera socket
                val unixStatus = try {
                    val lines = File("/proc/net/unix").readLines()
                        .filter { it.contains("camera", true) }
                    if (lines.isEmpty()) "нет" else "НАЙДЕН: ${lines.first().trim().take(80)}"
                } catch (_: Exception) { "?" }

                // Log only on change
                val changed = socketStatus != prevSocket || unixStatus != prevUnix
                if (changed) {
                    val msg = "socket=$socketStatus | unix=$unixStatus"
                    log("$ts ИЗМЕНЕНИЕ: $msg")
                    updateNotif(msg.take(60))
                    prevSocket = socketStatus
                    prevUnix = unixStatus
                }

                delay(500)
            }
        }

        return START_STICKY
    }

    private fun log(line: String) {
        buffer.addLast(line)
        if (buffer.size > 1000) buffer.removeFirst()
        _lines.value = buffer.toList()
        try { logFile(this).appendText("$line\n") } catch (_: Exception) {}
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("gCam Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "gCam Monitor", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        log("=== STOP ${Date()} ===")
        super.onDestroy()
    }
}
