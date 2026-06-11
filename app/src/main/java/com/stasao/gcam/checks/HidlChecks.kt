package com.stasao.gcam

import java.io.File

internal fun DiagnosticsEngine.checkHidlCameraProvider(): CheckResult {
    val sb = StringBuilder()

    run {
        val r = shell("service", "list")
        val providerLines = r.stdout.lines()
            .filter { it.contains("provider", true) || it.contains("camera", true) }
        if (providerLines.isNotEmpty()) {
            sb.appendLine("service list (camera/provider):")
            providerLines.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("service list: provider/camera строки не найдены")
        }
    }

    run {
        val svcName = "android.hardware.camera.provider@2.4/legacy/0"
        val r = shell("dumpsys", svcName)
        sb.appendLine("\ndumpsys $svcName:")
        when {
            r.stdout.length > 10 -> sb.appendLine(r.stdout.lines().take(30).joinToString("\n"))
            r.stderr.isNotEmpty() -> sb.appendLine("  ${r.stderr.take(150)}")
            else -> sb.appendLine("  нет вывода (exit=${r.exitCode})")
        }
    }

    val hidlLibs = listOf(
        "/vendor/lib/hw/android.hardware.camera.provider@2.4-impl.so",
        "/vendor/lib64/hw/android.hardware.camera.provider@2.4-impl.so",
        "/vendor/lib/hw/android.hardware.camera.provider@2.5-impl.so",
        "/vendor/lib64/hw/android.hardware.camera.provider@2.5-impl.so"
    ).filter { File(it).exists() }

    if (hidlLibs.isNotEmpty()) {
        sb.appendLine("\nHIDL camera provider .so найдены:")
        hidlLibs.forEach { sb.appendLine("  $it") }
    } else {
        sb.appendLine("\nHIDL provider .so: не найдены (или скрыты)")
    }

    try {
        val camLines = File("/proc/net/unix").readLines()
            .filter { it.contains("camera", true) || it.contains("provider", true) }
        if (camLines.isNotEmpty()) {
            sb.appendLine("\n/proc/net/unix (camera/provider):")
            camLines.take(10).forEach { sb.appendLine("  ${it.trim()}") }
        }
    } catch (_: Exception) {}

    val hasProvider = sb.contains("provider", ignoreCase = true) &&
            !sb.contains("не найдены") && !sb.contains("нет вывода")
    return CheckResult("HIDL Camera Provider", if (hasProvider) CheckStatus.OK else CheckStatus.WARN, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkHwServiceManager(): CheckResult {
    val r = shell("dumpsys", "hwservicemanager")
    if (r.stdout.isEmpty()) return CheckResult(
        "hwservicemanager", CheckStatus.WARN, "Нет вывода (exit=${r.exitCode}): ${r.stderr.take(120)}")
    val lines = r.stdout.lines()
    val cameraLines = lines.filter { line ->
        listOf("camera", "evs", "avm", "video", "provider").any { line.contains(it, true) }
    }
    val detail = buildString {
        if (cameraLines.isNotEmpty()) {
            appendLine("Camera/EVS/Provider сервисы:")
            cameraLines.forEach { appendLine("  $it") }
            appendLine()
        } else {
            appendLine("Camera-related сервисы: не зарегистрированы")
            appendLine()
        }
        appendLine("Всего HIDL сервисов: ${lines.count { it.contains("::")  }}")
    }
    return CheckResult("hwservicemanager", if (cameraLines.isNotEmpty()) CheckStatus.OK else CheckStatus.WARN, detail.trim())
}

internal fun DiagnosticsEngine.checkQCarCamProcess(): CheckResult {
    val sb = StringBuilder()
    val pid = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "init.svc_debug_pid.qcarcam_hal", "") as String).trim().toIntOrNull()
    } catch (_: Exception) { null }

    if (pid == null) return CheckResult("QCarCam HAL (procfs)", CheckStatus.WARN,
        "PID qcarcam_hal не найден в системных свойствах")
    sb.appendLine("qcarcam_hal PID: $pid")

    try {
        val cmdline = File("/proc/$pid/cmdline").readBytes()
            .map { if (it == 0.toByte()) ' ' else it.toInt().toChar() }
            .joinToString("").trim()
        sb.appendLine("cmdline: $cmdline")
    } catch (e: Exception) { sb.appendLine("cmdline: ${e.message}") }

    val camDevs = mutableListOf<String>()
    try {
        File("/proc/$pid/fd").listFiles()?.forEach { fd ->
            try {
                val target = fd.canonicalPath
                if (listOf("video", "socket", "camera", "v4l", "/dev/", "ion").any { target.contains(it, true) })
                    camDevs.add("  fd/${fd.name} → $target")
            } catch (_: Exception) {}
        } ?: sb.appendLine("fd/: нет доступа (нужен shell)")
    } catch (e: Exception) { sb.appendLine("fd/: ${e.message}") }

    if (camDevs.isNotEmpty()) {
        sb.appendLine("Файловые дескрипторы (camera/video/socket):")
        camDevs.forEach { sb.appendLine(it) }
    }

    try {
        val unixLines = File("/proc/$pid/net/unix").readLines()
            .filter { it.contains("camera", true) || it.contains("qcar", true) }
        if (unixLines.isNotEmpty()) {
            sb.appendLine("\nUnix sockets процесса:")
            unixLines.forEach { sb.appendLine("  ${it.trim()}") }
        }
    } catch (_: Exception) {}

    try {
        val cameraLibs = File("/proc/$pid/maps").readLines()
            .filter { line ->
                listOf("/dev/", "qcarcam", "camera", "v4l", "video").any { line.contains(it, true) }
                && !line.contains("dalvik") && !line.contains("dex")
            }.take(20)
        if (cameraLibs.isNotEmpty()) {
            sb.appendLine("\nmaps (camera/qcarcam/v4l):")
            cameraLibs.forEach { sb.appendLine("  ${it.trim().take(100)}") }
        }
    } catch (_: Exception) {}

    if (camDevs.isEmpty()) {
        val r = shell("ls", "-la", "/proc/$pid/fd")
        if (r.stdout.isNotEmpty()) {
            val interesting = r.stdout.lines()
                .filter { line -> listOf("video", "socket", "camera", "v4l").any { line.contains(it, true) } }
            if (interesting.isNotEmpty()) {
                sb.appendLine("\nShell ls fd (camera/video/socket):")
                interesting.forEach { sb.appendLine("  $it") }
            }
        }
    }

    val socketR = shell("ls", "-la", "/dev/socket/camera/")
    if (socketR.stdout.isNotEmpty()) sb.appendLine("\n/dev/socket/camera/ (shell):\n${socketR.stdout}")
    else sb.appendLine("\n/dev/socket/camera/ (shell): ${socketR.stderr.take(60)}")

    return CheckResult("QCarCam HAL (procfs)", CheckStatus.OK, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkQCarCamHidl(): CheckResult {
    val sb = StringBuilder()
    val qcarInterface = "vendor.qti.automotive.qcarcam@1.0::IQcarCamera"
    val qcarInstance  = "default"

    val lshalR = shell("lshal")
    if (lshalR.stdout.isNotEmpty()) {
        val camLines = lshalR.stdout.lines().filter { line ->
            listOf("camera", "qcar", "evs", "video").any { line.contains(it, true) }
        }
        if (camLines.isNotEmpty()) {
            sb.appendLine("lshal camera/qcar/evs:")
            camLines.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("lshal: camera/qcar строки не найдены")
        }
    } else {
        sb.appendLine("lshal: нет вывода (${lshalR.stderr.take(80)})")
    }

    for (svcName in listOf(
        "$qcarInterface/$qcarInstance",
        "vendor.qti.automotive.qcarcam@1.0::IQcarCamera/default",
        "vendor.qti.automotive.qcarcam@1.0"
    )) {
        val r = shell("dumpsys", svcName)
        if (r.stdout.length > 5) {
            sb.appendLine("\ndumpsys $svcName:")
            sb.appendLine(r.stdout.lines().take(30).joinToString("\n"))
            break
        }
    }

    var hwBinder: Any? = null
    try {
        val hwBinderClass = Class.forName("android.os.HwBinder")
        val getServiceMethod = hwBinderClass.getMethod("getService", String::class.java, String::class.java)
        hwBinder = try {
            getServiceMethod.invoke(null, qcarInterface, qcarInstance)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            null.also { sb.appendLine("\nHwBinder.getService: ${e.cause?.message?.take(80)}") }
        }

        if (hwBinder != null) {
            sb.appendLine("\nHwBinder.getService($qcarInterface): ПОЛУЧЕН!")
            sb.appendLine("  Класс: ${hwBinder.javaClass.name}")
            qcarCamTransact(hwBinder, sb)
        } else {
            sb.appendLine("\nHwBinder.getService: null")
        }
    } catch (e: Exception) {
        sb.appendLine("\nHwBinder: ${e.message?.take(80)}")
    }

    val vendorLibs = (File("/vendor/lib64").listFiles { f ->
        f.name.contains("qcarcam", true) || f.name.contains("qcar_cam", true)
            || (f.name.contains("qcar", true) && f.name.endsWith(".so"))
    } ?: emptyArray()).map { it.name }
    if (vendorLibs.isNotEmpty()) sb.appendLine("\nQCarCam libs в /vendor/lib64: ${vendorLibs.joinToString()}")

    val hasQCar = sb.contains("IQcarCamera") || sb.contains("ПОЛУЧЕН")
    return CheckResult("QCarCam HIDL", if (hasQCar) CheckStatus.OK else CheckStatus.WARN, sb.toString().trim())
}

// All HIDL transact calls for QCarCam — isolated to keep checkQCarCamHidl readable
private fun DiagnosticsEngine.qcarCamTransact(binder: Any, sb: StringBuilder) {
    val hwParcelClass = try {
        Class.forName("android.os.HwParcel")
    } catch (t: Throwable) {
        sb.appendLine("  HwParcel недоступен: ${t.message?.take(60)}")
        return
    }

    val newParcel  = { hwParcelClass.getDeclaredConstructor().newInstance() }
    val writeToken = hwParcelClass.getMethod("writeInterfaceToken", String::class.java)
    val release    = hwParcelClass.getMethod("release")
    val verifySuc  = hwParcelClass.getMethod("verifySuccess")
    val readInt32  = hwParcelClass.getMethod("readInt32")
    val writeInt32 = hwParcelClass.getMethod("writeInt32", Int::class.java)
    val transact   = binder.javaClass.getMethod(
        "transact", Int::class.java, hwParcelClass, hwParcelClass, Int::class.java)

    val iface = "vendor.qti.automotive.qcarcam@1.0::IQcarCamera"

    fun safeTransact(methodCode: Int, writeArgs: ((Any) -> Unit)? = null): Pair<Int?, List<Int>> {
        val req = newParcel()
        val rep = newParcel()
        return try {
            writeToken.invoke(req, iface)
            writeArgs?.invoke(req)
            transact.invoke(binder, methodCode, req, rep, 0)
            verifySuc.invoke(rep)
            val status = readInt32.invoke(rep) as? Int
            val extra = mutableListOf<Int>()
            repeat(16) {
                val v = try { readInt32.invoke(rep) as? Int } catch (_: Throwable) { null }
                    ?: return@repeat
                extra.add(v)
            }
            Pair(status, extra)
        } catch (t: Throwable) {
            val inner = t.cause ?: t
            val msg = inner.message?.take(70) ?: inner.javaClass.simpleName
            sb.appendLine("  method=$methodCode [${inner.javaClass.simpleName}]: $msg")
            Pair(null, emptyList())
        } finally {
            try { release.invoke(req) } catch (_: Throwable) {}
            try { release.invoke(rep) } catch (_: Throwable) {}
        }
    }

    // Method 1 — no args; first user-defined HIDL method
    val (s1, d1) = safeTransact(1)
    var sessionHandle: Int? = null
    if (s1 != null) {
        sb.appendLine("  method=1 status=$s1 (${if (s1 == 0) "OK" else "FAIL"})")
        if (d1.isNotEmpty()) {
            sessionHandle = d1.firstOrNull()
            val hex = d1.map { "0x${it.toUInt().toString(16).padStart(8,'0')}" }.joinToString(" ")
            sb.appendLine("  method=1 hex: $hex")
            sb.appendLine("  method=1 → possible handle: 0x${sessionHandle?.toUInt()?.toString(16)}")
        }
    }

    // method=1 returned two input descriptors (idx=0 and idx=1).
    // Trying method=2 as openSession/openStream(inputId: uint32):
    for (inputId in 0..1) {
        val (s2, d2) = safeTransact(2) { req -> writeInt32.invoke(req, inputId) }
        sb.appendLine("  method=2(inputId=$inputId) status=$s2" +
            if (!d2.isNullOrEmpty()) " hex=${d2.take(6).map { "0x${it.toUInt().toString(16)}" }}" else "")
        if (s2 == 0) sb.appendLine("    *** method=2(inputId=$inputId) STATUS=0 = SUCCESS! ***")
    }

    // Method 2 — no args
    val (s2n, d2n) = safeTransact(2)
    sb.appendLine("  method=2(no args) status=$s2n" +
        if (!d2n.isNullOrEmpty()) " hex=${d2n.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")

    // Methods 3..7 — no args, probe the rest of the API surface
    for (m in 3..7) {
        val (sm, dm) = safeTransact(m)
        sb.appendLine("  method=$m(no args) status=$sm" +
            if (!dm.isNullOrEmpty()) " hex=${dm.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")
    }

    // Methods 3..4 — inputId=0 as uint32
    for (m in 3..4) {
        val (smh, dmh) = safeTransact(m) { req -> writeInt32.invoke(req, 0) }
        sb.appendLine("  method=$m(inputId=0) status=$smh" +
            if (!dmh.isNullOrEmpty()) " hex=${dmh.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")
    }

    // strings on vendor lib — look for user-defined method names in BpHwQcarCamera
    val libPath = "/vendor/lib64/vendor.qti.automotive.qcarcam@1.0.so"
    val strR = shell("strings", libPath, timeoutMs = 3000)
    if (strR.stdout.isNotEmpty()) {
        val interesting = strR.stdout.lines()
            .filter { it.length in 5..80 }
            .filter { line ->
                listOf("QcarCam", "IQcar", "session", "input", "frame", "stream",
                    "open", "start", "stop", "init", "getAvail", "Camera",
                    "method", "transact", "getCameras")
                    .any { line.contains(it, true) }
                && !line.startsWith("#") && !line.startsWith("//")
            }.distinct().take(30)
        if (interesting.isNotEmpty()) {
            sb.appendLine("\nstrings $libPath:")
            interesting.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("\nstrings: нет интересных строк (всего ${strR.stdout.lines().size})")
        }
    } else {
        val f = File(libPath)
        sb.appendLine("\n$libPath: exists=${f.exists()} size=${f.length()}")
        sb.appendLine("strings: ${strR.stderr.take(80)}")
    }

    // Search for .hal files — would reveal the exact method signatures
    for (searchDir in listOf("/vendor", "/system", "/odm")) {
        val r = shell("find", searchDir, "-name", "*.hal", "-path", "*qcarcam*", timeoutMs = 2000)
        if (r.stdout.isNotEmpty()) {
            sb.appendLine("\n.hal файлы ($searchDir): ${r.stdout.take(200)}")
            r.stdout.lines().firstOrNull()?.let { halPath ->
                try {
                    val content = File(halPath).readText().take(800)
                    sb.appendLine("HAL содержимое:\n$content")
                } catch (_: Exception) {}
            }
        }
    }

    // Find the surround-view / 360° system APK that uses QCarCam
    val surroundPkg = try {
        context.packageManager.getInstalledPackages(0)
            .filter { it.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            .filter { pkg ->
                listOf("surround", "360", "parking", "avm", "qcar", "evs", "view")
                    .any { pkg.packageName.contains(it, true) }
            }.map { it.packageName }
    } catch (_: Exception) { emptyList() }
    if (surroundPkg.isNotEmpty()) {
        sb.appendLine("\nSystem APK (surround/360/avm): ${surroundPkg.joinToString()}")
    }
}
