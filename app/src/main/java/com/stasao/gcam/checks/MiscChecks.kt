package com.stasao.gcam

import android.content.Intent
import android.content.ComponentName


internal fun DiagnosticsEngine.checkInstalledPackages(): CheckResult {
    val sb = StringBuilder()

    // All installed packages with camera/DVR in name
    val cameraPkgs = context.packageManager.getInstalledPackages(0)
        .map { it.packageName }
        .filter { it.contains("camera", true) || it.contains("dvr", true) }
    if (cameraPkgs.isNotEmpty()) sb.appendLine("camera/dvr APKs: ${cameraPkgs.joinToString()}")

    // Automotive camera/parking apps — key target: com.ecarx.parking
    val autoPkgs = context.packageManager.getInstalledPackages(0)
        .filter { pkg ->
            listOf("parking", "surround", "360", "avm", "qcar", "ecarx", "evs", "adas")
                .any { pkg.packageName.contains(it, true) }
        }
    if (autoPkgs.isNotEmpty()) {
        sb.appendLine("\nAutomotive/Surround APKs:")
        for (pkg in autoPkgs) {
            val apkPath = try {
                context.packageManager.getPackageInfo(pkg.packageName, 0).applicationInfo?.sourceDir
            } catch (_: Exception) { null }
            val isSystem = pkg.applicationInfo?.flags
                ?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            sb.appendLine("  ${pkg.packageName} [${if (isSystem) "system" else "user"}]")
            if (apkPath != null) {
                sb.appendLine("    APK: $apkPath")
                sb.appendLine("    → adb pull \"$apkPath\" ecarx.apk")
            }
        }
    } else {
        sb.appendLine("Automotive APKs: не найдены (parking/surround/360/avm)")
    }

    // Find PIDs of automotive/parking processes and inspect their native libraries
    val targetPkgs = autoPkgs.map { it.packageName }.toSet()
    if (targetPkgs.isNotEmpty()) {
        val procDir = java.io.File("/proc")
        val found = mutableListOf<String>()
        procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?.forEach { pidDir ->
                try {
                    val cmdline = java.io.File(pidDir, "cmdline").readBytes()
                        .map { if (it == 0.toByte()) ' ' else it.toInt().toChar() }
                        .joinToString("").trim()
                    if (targetPkgs.any { cmdline.contains(it) }) {
                        val pid = pidDir.name
                        found.add("\nПроцесс pid=$pid: $cmdline")
                        // Look for camera/qcarcam libs in maps
                        try {
                            val maps = java.io.File("/proc/$pid/maps").readLines()
                                .filter { line ->
                                    listOf("qcarcam", "camera", "evs", "libecarx", "libcar", "qcar")
                                        .any { line.contains(it, true) }
                                    && line.contains(".so")
                                }.distinct().take(20)
                            if (maps.isNotEmpty()) {
                                found.add("  Библиотеки:")
                                maps.forEach { found.add("  ${it.trim().substringAfterLast(" ").take(80)}") }
                            } else {
                                found.add("  (maps нет доступа или камера-libs не найдены)")
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        if (found.isNotEmpty()) sb.appendLine(found.joinToString("\n"))
    }

    val status = if (autoPkgs.isNotEmpty()) CheckStatus.OK else CheckStatus.WARN
    return CheckResult("Camera/Automotive APK", status,
        sb.toString().trim().ifEmpty { "Automotive APKs не найдены" })
}

internal fun DiagnosticsEngine.checkECarXCarService(): CheckResult {
    val sb = StringBuilder()

    // Try ServiceManager.getService("ecarxcar_service") via reflection
    val smClass = try { Class.forName("android.os.ServiceManager") } catch (_: Exception) { null }
    val getService = smClass?.getMethod("getService", String::class.java)

    val serviceNames = listOf("ecarxcar_service", "car_signal", "car_publicattribute")
    for (svcName in serviceNames) {
        val binder = try { getService?.invoke(null, svcName) } catch (_: Exception) { null }
        sb.appendLine("ServiceManager.getService(\"$svcName\"): ${if (binder != null) "FOUND binder=$binder" else "null / denied"}")
    }

    // Try to resolve PasFunc$$Creator class (exists in system lib if adaptapi is available)
    val pasFuncClasses = listOf(
        "ecarx.fw.api.PasFunc.PasFunc\$\$Creator",
        "ecarx.fw.api.PasFunc.PasFuncImpl",
        "ecarx.car.ECarXCar",
        "ecarx.car.IECarXCar",
        "com.ecarx.xui.adaptapi.car.CarImpl",
        "com.ecarx.xui.adaptapi.ECarXCarProxy"
    )
    sb.appendLine("\nECarX system class availability:")
    for (cls in pasFuncClasses) {
        val found = try { Class.forName(cls); true } catch (_: ClassNotFoundException) { false } catch (_: Exception) { false }
        sb.appendLine("  ${if (found) "OK" else "--"} $cls")
    }

    // Try to resolve IEvsCamera from adaptapi (if shared library is on classpath)
    val evsCls = try { Class.forName("com.ecarx.xui.adaptapi.evs.EVSImp"); true } catch (_: Exception) { false }
    sb.appendLine("  ${if (evsCls) "OK" else "--"} com.ecarx.xui.adaptapi.evs.EVSImp")

    // Check if we can resolve com.ecarx.parking Activity via PackageManager
    val parkingPkg = "com.ecarx.parking"
    val parkingActivity = "com.ecarx.parking.MainAvmActivity"
    try {
        context.packageManager.getActivityInfo(
            ComponentName(parkingPkg, parkingActivity), 0)
        sb.appendLine("\ncom.ecarx.parking.MainAvmActivity: RESOLVED (можно запустить через Intent)")
        sb.appendLine("  Intent action: android.intent.action.MAIN / ACTION_TYPE=<open/close>")
    } catch (_: Exception) {
        sb.appendLine("\ncom.ecarx.parking.MainAvmActivity: не найден")
    }

    val hasService = sb.contains("FOUND")
    val hasClasses = sb.contains("\n  OK ")
    return CheckResult("ECarX Car Service",
        if (hasService || hasClasses) CheckStatus.OK else CheckStatus.WARN,
        sb.toString().trim())
}
