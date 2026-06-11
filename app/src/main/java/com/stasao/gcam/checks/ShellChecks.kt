package com.stasao.gcam

internal fun DiagnosticsEngine.checkShellServiceList(): CheckResult {
    val r = shell("service", "list")
    if (r.stdout.isEmpty()) return CheckResult(
        "service list", CheckStatus.WARN, "Нет вывода (exit=${r.exitCode}): ${r.stderr.take(100)}")
    val keywords = listOf("camera", "cam", "evs", "video", "vin", "avm", "ecarx")
    val found = r.stdout.lines().filter { line -> keywords.any { line.contains(it, true) } }
    val total = r.stdout.lines().size
    return if (found.isNotEmpty())
        CheckResult("service list (camera/ecarx)", CheckStatus.OK,
            "Всего сервисов: $total\nCamera/ECarX-related:\n${found.joinToString("\n")}")
    else
        CheckResult("service list (camera/ecarx)", CheckStatus.INFO,
            "Всего сервисов: $total. Camera/EVS/ECarX сервисы не найдены.")
}

internal fun DiagnosticsEngine.checkShellLsZSocket(): CheckResult {
    val r = shell("ls", "-laZ", "/dev/socket/")
    if (r.stdout.isEmpty()) return CheckResult(
        "ls -laZ /dev/socket/", CheckStatus.WARN, "Нет вывода: ${r.stderr.take(100)}")
    val lines = r.stdout.lines()
    val cameraLines = lines.filter { it.contains("camera", true) || it.contains("evs", true) }
    val detail = if (cameraLines.isNotEmpty())
        "Camera сокеты:\n${cameraLines.joinToString("\n")}\n\nВсе сокеты (${lines.size}):\n${r.stdout.take(800)}"
    else
        "Camera сокеты не найдены\n\nВсе сокеты:\n${r.stdout.take(800)}"
    return CheckResult("ls -laZ /dev/socket/", if (cameraLines.isNotEmpty()) CheckStatus.OK else CheckStatus.INFO, detail)
}
