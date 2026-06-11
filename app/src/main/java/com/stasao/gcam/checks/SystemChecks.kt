package com.stasao.gcam

import android.os.Build
import java.io.File

internal fun DiagnosticsEngine.checkDeviceInfo() = CheckResult(
    "Устройство", CheckStatus.INFO,
    "Manufacturer: ${Build.MANUFACTURER}\n" +
    "Model: ${Build.MODEL}\n" +
    "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
    "Hardware: ${Build.HARDWARE}\n" +
    "Board: ${Build.BOARD}"
)

internal fun DiagnosticsEngine.checkSystemProps(): CheckResult {
    val propNames = listOf(
        "ro.camera.hal.version", "ro.hardware.camera",
        "ro.board.platform", "ro.vendor.product.device",
        "ro.odm.product.device", "ro.hardware",
    )
    val props = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        propNames.mapNotNull { name ->
            val v = get.invoke(null, name, "") as String
            if (v.isNotEmpty()) "$name=$v" else null
        }
    } catch (e: Exception) {
        listOf("(SystemProperties недоступен: ${e.message})")
    }
    return CheckResult("System Properties", CheckStatus.INFO,
        props.ifEmpty { listOf("Все свойства пусты") }.joinToString("\n"))
}

internal fun DiagnosticsEngine.checkProcessesViaProcfs(): CheckResult {
    val keywords = listOf("camera", "cameraserver", "dvr", "evs", "avm", "video", "display")
    val found = mutableListOf<String>()
    val procDir = File("/proc")
    try {
        procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?.sortedBy { it.name.toInt() }
            ?.forEach { pidDir ->
                try {
                    val cmdline = File(pidDir, "cmdline")
                        .readBytes()
                        .map { if (it == 0.toByte()) ' ' else it.toInt().toChar() }
                        .joinToString("").trim()
                    if (cmdline.isNotEmpty() && keywords.any { cmdline.contains(it, true) }) {
                        val name = File(pidDir, "status").readLines()
                            .firstOrNull { it.startsWith("Name:") }
                            ?.removePrefix("Name:")?.trim() ?: ""
                        found.add("pid=${pidDir.name} [$name] $cmdline".take(120))
                    }
                } catch (_: Exception) {}
            }
    } catch (e: Exception) {
        return CheckResult("Процессы (procfs)", CheckStatus.WARN, "/proc недоступен: ${e.message}")
    }
    return if (found.isNotEmpty())
        CheckResult("Процессы (procfs)", CheckStatus.OK, found.joinToString("\n"))
    else
        CheckResult("Процессы (procfs)", CheckStatus.WARN, "camera/evs/avm/dvr процессы не найдены через /proc")
}

internal fun DiagnosticsEngine.checkSeLinux(): CheckResult {
    val sb = StringBuilder()
    val mode = try { File("/sys/fs/selinux/enforce").readText().trim() } catch (_: Exception) { null }
    when (mode) {
        "1" -> sb.appendLine("SELinux: ENFORCING (блокирует доступ к /dev/socket/camera)")
        "0" -> sb.appendLine("SELinux: PERMISSIVE (блокировки нет!)")
        null -> sb.appendLine("SELinux: не удалось прочитать /sys/fs/selinux/enforce")
        else -> sb.appendLine("SELinux: unknown ($mode)")
    }
    val ourContext = try { File("/proc/self/attr/current").readText().trim() } catch (_: Exception) { "недоступен" }
    sb.appendLine("Наш контекст: $ourContext")
    try {
        val unixLines = File("/proc/net/unix").readLines()
        val socketLine = unixLines.firstOrNull { it.contains("/dev/socket/camera") }
        if (socketLine != null) sb.appendLine("Socket в /proc/net/unix: $socketLine")
        else sb.appendLine("Socket в /proc/net/unix: не найден (SELinux скрывает)")
    } catch (e: Exception) {
        sb.appendLine("/proc/net/unix: ${e.message}")
    }
    if (mode == "1") {
        sb.appendLine("\n→ Для теста: adb shell setenforce 0")
        sb.appendLine("  После этого — повтори диагностику")
    }
    val status = when (mode) { "0" -> CheckStatus.OK; "1" -> CheckStatus.WARN; else -> CheckStatus.INFO }
    return CheckResult("SELinux Status", status, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkOwnGids(): CheckResult {
    val sb = StringBuilder()
    try {
        val status = File("/proc/self/status").readLines()
        val uid   = status.firstOrNull { it.startsWith("Uid:") }?.trim() ?: "?"
        val gid   = status.firstOrNull { it.startsWith("Gid:") }?.trim() ?: "?"
        val groups = status.firstOrNull { it.startsWith("Groups:") }
        sb.appendLine(uid)
        sb.appendLine(gid)
        if (groups == null) {
            sb.appendLine("Groups: строка не найдена в /proc/self/status")
        } else {
            sb.appendLine(groups)
            val gids = groups.removePrefix("Groups:").trim().split("\\s+".toRegex())
                .mapNotNull { it.toIntOrNull() }
            val hasCameraGid = gids.contains(1006)
            sb.appendLine()
            sb.appendLine("GID 1006 (camera): ${if (hasCameraGid) "ЕСТЬ ✓" else "НЕТ ✗"}")
            val knownGids = mapOf(
                1000 to "system", 1001 to "radio", 1002 to "bluetooth",
                1003 to "graphics", 1004 to "input", 1005 to "audio",
                1006 to "camera", 1007 to "log", 1023 to "media",
                1028 to "sdcard_r", 1029 to "sdcard_rw",
                3002 to "net_bt", 3003 to "inet", 3004 to "net_raw"
            )
            val labeled = gids.map { g -> knownGids[g]?.let { "$g($it)" } ?: "$g" }
            sb.appendLine("Все GID: ${labeled.joinToString(" ")}")
            if (!hasCameraGid) {
                sb.appendLine("\n→ Камера заблокирована DAC: нет GID camera(1006)")
                sb.appendLine("→ adb shell pm grant ${context.packageName} android.permission.CAMERA")
            }
            return CheckResult("Собственные GID", if (hasCameraGid) CheckStatus.OK else CheckStatus.WARN, sb.toString().trim())
        }
    } catch (e: Exception) {
        sb.appendLine("/proc/self/status: ${e.message}")
    }
    return CheckResult("Собственные GID", CheckStatus.INFO, sb.toString().trim())
}

