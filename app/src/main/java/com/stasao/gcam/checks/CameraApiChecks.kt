package com.stasao.gcam

import java.io.File

internal fun DiagnosticsEngine.checkVideoDevices(): CheckResult {
    val videoDevs = File("/dev").listFiles { f -> f.name.startsWith("video") }?.sortedBy { it.name }
    if (videoDevs.isNullOrEmpty()) {
        val v4lDevs = File("/dev/v4l").listFiles()
        return if (!v4lDevs.isNullOrEmpty()) {
            CheckResult("/dev/video* (V4L2)", CheckStatus.WARN,
                "/dev/video* не найдены, но /dev/v4l/ есть: ${v4lDevs.joinToString { it.name }}")
        } else {
            CheckResult("/dev/video* (V4L2)", CheckStatus.FAIL,
                "Устройства /dev/video* не найдены (V4L2 недоступен или скрыт SELinux)")
        }
    }
    val details = videoDevs.joinToString("\n") { f ->
        "${f.name}: read=${f.canRead()} write=${f.canWrite()}"
    }
    val anyReadable = videoDevs.any { it.canRead() }
    return CheckResult(
        "/dev/video* (V4L2)",
        if (anyReadable) CheckStatus.OK else CheckStatus.WARN,
        if (anyReadable) details else "$details\n(нет прав — нужен root/system uid)"
    )
}

internal fun DiagnosticsEngine.checkV4L2NodeNames(): CheckResult {
    val nodes = mutableListOf<String>()
    for (i in 0..80) {
        val sysDir = File("/sys/class/video4linux/video$i")
        if (!sysDir.exists()) continue
        val name = try { File(sysDir, "name").readText().trim() } catch (_: Exception) { "?" }
        val devNum = try { File(sysDir, "dev").readText().trim() } catch (_: Exception) { "" }
        val readable = File("/dev/video$i").canRead()
        val isCam = listOf("cam", "camera", "vin", "csi", "isp", "sensor", "capture", "mipi")
            .any { name.contains(it, true) }
        nodes.add("video$i: \"$name\" dev=$devNum read=$readable${if (isCam) " <-- CAMERA" else ""}")
    }
    return if (nodes.isNotEmpty()) {
        val hasCam = nodes.any { it.contains("<-- CAMERA") }
        CheckResult("/sys V4L2 Node Names", if (hasCam) CheckStatus.OK else CheckStatus.INFO, nodes.joinToString("\n"))
    } else {
        CheckResult("/sys V4L2 Node Names", CheckStatus.WARN, "/sys/class/video4linux/ пуст или недоступен")
    }
}

internal fun DiagnosticsEngine.checkCameraHal(): CheckResult {
    val dirs = listOf("/vendor/lib/hw", "/vendor/lib64/hw", "/system/lib/hw", "/system/lib64/hw")
    val found = dirs.flatMap { dir ->
        File(dir).listFiles { f -> f.name.startsWith("camera") }
            ?.map { "$dir/${it.name}" } ?: emptyList()
    }
    return if (found.isNotEmpty())
        CheckResult("Camera HAL (.so)", CheckStatus.OK, found.joinToString("\n"))
    else
        CheckResult("Camera HAL (.so)", CheckStatus.WARN,
            "camera.*.so не найдены в /vendor/lib*/hw/ или /system/lib*/hw/")
}
