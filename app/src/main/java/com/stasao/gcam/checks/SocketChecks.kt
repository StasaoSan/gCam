package com.stasao.gcam

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

internal fun DiagnosticsEngine.checkCameraSocketDir(): CheckResult {
    val sb = StringBuilder()
    val cameraSocketPath = "/dev/socket/camera"
    val socketDir = File(cameraSocketPath)

    if (!socketDir.exists()) {
        return CheckResult("Camera Socket Dir", CheckStatus.WARN, "$cameraSocketPath не существует")
    }
    if (socketDir.isFile) return tryConnectSocket(cameraSocketPath, "camera")
    if (!socketDir.isDirectory) {
        return CheckResult("Camera Socket Dir", CheckStatus.INFO, "$cameraSocketPath: тип неизвестен")
    }

    sb.appendLine("$cameraSocketPath — это ДИРЕКТОРИЯ")

    val children = try { socketDir.listFiles() } catch (_: Exception) { null }
    if (children == null) {
        sb.appendLine("Содержимое: нет доступа (Permission denied)")
        sb.appendLine("→ Нужен GID camera(1006) для чтения директории")
        val r = shell("ls", "-la", cameraSocketPath)
        when {
            r.stdout.isNotEmpty() -> sb.appendLine("ls -la вывод:\n${r.stdout}")
            r.stderr.isNotEmpty() -> sb.appendLine("ls -la: ${r.stderr}")
        }
        return CheckResult("Camera Socket Dir", CheckStatus.WARN, sb.toString().trim())
    }

    if (children.isEmpty()) {
        sb.appendLine("Директория пуста")
        return CheckResult("Camera Socket Dir", CheckStatus.INFO, sb.toString().trim())
    }

    sb.appendLine("Найдено ${children.size} файлов:")
    for (child in children) {
        val perms = "${if (child.canRead()) "r" else "-"}${if (child.canWrite()) "w" else "-"}"
        sb.appendLine("  ${child.name} [$perms]")
    }
    sb.appendLine()

    var connectedAny = false
    for (child in children) {
        try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(child.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 500
            val bytes = try {
                val buf = ByteArray(64)
                val n = socket.inputStream.read(buf)
                if (n > 0) "прочитано $n байт: ${buf.take(n).joinToString(" ") { "%02x".format(it) }}"
                else "подключён (данных нет)"
            } catch (_: Exception) { "подключён (нет ответа за 500мс)" }
            socket.close()
            sb.appendLine("  ${child.name}: ПОДКЛЮЧЁН — $bytes")
            connectedAny = true
        } catch (e: Exception) {
            val reason = when {
                e.message?.contains("ECONNREFUSED") == true -> "ECONNREFUSED"
                e.message?.contains("EACCES") == true       -> "EACCES (DAC/SELinux)"
                e.message?.contains("ENOTSOCK") == true     -> "не сокет"
                else -> e.message?.take(50) ?: "ошибка"
            }
            sb.appendLine("  ${child.name}: $reason")
        }
    }

    return CheckResult("Camera Socket Dir",
        if (connectedAny) CheckStatus.OK else CheckStatus.WARN,
        sb.toString().trim())
}

private fun DiagnosticsEngine.tryConnectSocket(path: String, name: String): CheckResult {
    return try {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.RESERVED))
        socket.soTimeout = 500
        val info = try {
            val buf = ByteArray(64)
            val n = socket.inputStream.read(buf)
            if (n > 0) "прочитано $n байт" else "подключён"
        } catch (_: Exception) { "подключён (нет данных)" }
        socket.close()
        CheckResult("Camera Socket Dir", CheckStatus.OK, "$path: ПОДКЛЮЧЁН — $info")
    } catch (e: Exception) {
        CheckResult("Camera Socket Dir", CheckStatus.WARN, "$path: ${e.message?.take(80)}")
    }
}
