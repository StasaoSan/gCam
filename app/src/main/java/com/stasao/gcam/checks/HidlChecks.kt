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
    } catch (t: Throwable) {
        sb.appendLine("\nHwBinder crashed: ${t.javaClass.simpleName}: ${t.message?.take(80)}")
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
    val writeInt64 = hwParcelClass.getMethod("writeInt64", Long::class.java)
    val transact   = binder.javaClass.getMethod(
        "transact", Int::class.java, hwParcelClass, hwParcelClass, Int::class.java)

    // IHwBinder and writeStrongBinder — needed for passing callback to openSession
    val iHwBinderCls    = try { Class.forName("android.os.IHwBinder") } catch (_: Throwable) { null }
    val writeStrongBinder = if (iHwBinderCls != null) {
        try { hwParcelClass.getMethod("writeStrongBinder", iHwBinderCls) } catch (_: Throwable) { null }
    } else null
    sb.appendLine("  HwParcel.writeStrongBinder: ${if (writeStrongBinder != null) "доступен" else "НЕ НАЙДЕН"}")

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
    // Method 2 — various argument signatures for openSession/openStream:

    // A) inputId as uint32 (already tried — returns non-zero QCarCamRet_e, NOT an exception)
    for (inputId in 0..1) {
        val (s2, d2) = safeTransact(2) { req -> writeInt32.invoke(req, inputId) }
        sb.appendLine("  method=2(u32 inputId=$inputId) status=$s2" +
            if (!d2.isNullOrEmpty()) " hex=${d2.take(6).map { "0x${it.toUInt().toString(16)}" }}" else "")
        if (s2 == 0) sb.appendLine("    *** STATUS=0 SUCCESS! ***")
    }

    // B) inputId as uint64
    for (inputId in 0..1) {
        val (s2u, d2u) = safeTransact(2) { req -> writeInt64.invoke(req, inputId.toLong()) }
        sb.appendLine("  method=2(u64 inputId=$inputId) status=$s2u" +
            if (!d2u.isNullOrEmpty()) " hex=${d2u.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")
        if (s2u == 0) sb.appendLine("    *** STATUS=0 SUCCESS! ***")
    }

    // C) inputId(u32) + config(u32) — maybe openSession takes (inputId, flags/config)
    for (inputId in 0..1) {
        val (s2c, d2c) = safeTransact(2) { req ->
            writeInt32.invoke(req, inputId)
            writeInt32.invoke(req, 0)       // flags/config = 0
        }
        sb.appendLine("  method=2(u32 inputId=$inputId, u32 flags=0) status=$s2c" +
            if (!d2c.isNullOrEmpty()) " hex=${d2c.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")
        if (s2c == 0) sb.appendLine("    *** STATUS=0 SUCCESS! ***")
    }

    // D) inputId(u32) + two u32 + null (maybe: inputId, numBufs, bufSize, nullCallback)
    val (s2d, d2d) = safeTransact(2) { req ->
        writeInt32.invoke(req, 0)   // inputId=0
        writeInt32.invoke(req, 3)   // numBuffers=3 (typical)
        writeInt32.invoke(req, 0)   // bufSize
        writeInt32.invoke(req, 0)   // null callback
    }
    sb.appendLine("  method=2(inputId=0, numBufs=3, bufSize=0, cb=null) status=$s2d" +
        if (!d2d.isNullOrEmpty()) " hex=${d2d.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")
    if (s2d == 0) sb.appendLine("    *** STATUS=0 SUCCESS! ***")

    // E) no args
    val (s2n, d2n) = safeTransact(2)
    sb.appendLine("  method=2(no args) status=$s2n" +
        if (!d2n.isNullOrEmpty()) " hex=${d2n.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")

    // F) inputId(u32) + writeStrongBinder(null) — proper HIDL null callback arg
    if (writeStrongBinder != null) {
        for (inputId in 0..1) {
            val (s2b, d2b) = safeTransact(2) { req ->
                writeInt32.invoke(req, inputId)
                writeStrongBinder.invoke(req, null)  // IQcarCameraStreamCB = null
            }
            sb.appendLine("  method=2(u32 inputId=$inputId, binder=null) status=$s2b" +
                if (!d2b.isNullOrEmpty()) " hex=${d2b.take(6).map { "0x${it.toUInt().toString(16)}" }}" else "")
            if (s2b == 0) sb.appendLine("    *** STATUS=0 SUCCESS! binder=null принят! ***")
            if (s2b != null && s2b != 1936206469.toInt())
                sb.appendLine("    *** ИНАЯ ОШИБКА — прогресс! status изменился с ${1936206469} ***")
        }
        // Also try: no inputId, just null binder
        val (s2b0, d2b0) = safeTransact(2) { req -> writeStrongBinder.invoke(req, null) }
        sb.appendLine("  method=2(no inputId, binder=null) status=$s2b0" +
            if (!d2b0.isNullOrEmpty()) " hex=${d2b0.take(4).map { "0x${it.toUInt().toString(16)}" }}" else "")
    } else {
        sb.appendLine("  method=2 writeStrongBinder: недоступен — нельзя передать binder")
    }

    // G) real IHwBinder stub + joinRpcThreadpool (for callbacks) + session probe

    // joinRpcThreadpool убран: нативный вызов модифицирует глобальный ProcessState → SIGABRT

    val qcarStubInstance = if (writeStrongBinder != null) {
        try {
            val dexBytes = context.assets.open("qcarcam_stub.dex").readBytes()
            val loaderCls = Class.forName("dalvik.system.InMemoryDexClassLoader")
            val loaderCtor = loaderCls.getConstructor(java.nio.ByteBuffer::class.java, ClassLoader::class.java)
            val loader = loaderCtor.newInstance(
                java.nio.ByteBuffer.wrap(dexBytes),
                ClassLoader.getSystemClassLoader()
            ) as ClassLoader
            val stubClass = loader.loadClass("com.stasao.gcam.stub.QcarStreamCB")
            val inst = stubClass.getMethod("getInstance").invoke(null)
            sb.appendLine("  QcarStreamCB loaded: ${inst?.javaClass?.name}")
            inst
        } catch (e: Throwable) {
            sb.appendLine("  DEX load FAIL: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            null
        }
    } else null

    if (qcarStubInstance != null && writeStrongBinder != null) {
        val getLastCode = try { qcarStubInstance.javaClass.getMethod("getLastCode") } catch (_: Throwable) { null }
        // readStrongBinder() on HwParcel — to read IQcarCameraStream binder from openStream reply
        val readStrongBinderM = try { hwParcelClass.getMethod("readStrongBinder") } catch (_: Throwable) { null }
        sb.appendLine("  readStrongBinder method: ${if (readStrongBinderM != null) "found" else "NOT FOUND on HwParcel"}")

        val streamIfaceToken = "vendor.qti.automotive.qcarcam@1.0::IQcarCameraStream"

        // ── openStream probe: реальная сигнатура неизвестна, пробуем варианты ──
        // Логи показали: openStream(cb_only) → null binder
        //                openStream(inputId=0, null_cb) → handle=2 (ненулевой!)
        // Вывод: сигнатура, скорее всего, openStream(uint32 inputId, IQcarCameraStreamCB cb)
        fun tryOpenStream(label: String, writeArgs: (Any) -> Unit): Any? {
            val rq = newParcel(); val rp = newParcel()
            return try {
                writeToken.invoke(rq, iface)
                writeArgs(rq)
                transact.invoke(binder, 2, rq, rp, 0)
                try { verifySuc.invoke(rp) } catch (_: Throwable) {}
                var rsbEx: String? = null
                val streamB = readStrongBinderM?.let { m ->
                    try { m.invoke(rp) }
                    catch (e: Throwable) { rsbEx = "${(e.cause ?: e).javaClass.simpleName}:${(e.cause ?: e).message?.take(60)}"; null }
                }
                // Read raw int32s for diagnosis regardless of binder result
                val raw0 = try { readInt32.invoke(rp) as? Int } catch (_: Throwable) { null }
                val raw1 = try { readInt32.invoke(rp) as? Int } catch (_: Throwable) { null }
                val raw2 = try { readInt32.invoke(rp) as? Int } catch (_: Throwable) { null }
                sb.appendLine("  openStream[$label] binder=${streamB?.javaClass?.simpleName ?: "null"}${if (rsbEx != null) " EX=$rsbEx" else ""} raw=0x${raw0?.toUInt()?.toString(16)},0x${raw1?.toUInt()?.toString(16)},0x${raw2?.toUInt()?.toString(16)}")
                streamB
            } catch (e: Throwable) {
                val inner = e.cause ?: e
                sb.appendLine("  openStream[$label] ERROR: ${inner.javaClass.simpleName}: ${inner.message?.take(80)}")
                null
            } finally {
                try { release.invoke(rq) } catch (_: Throwable) {}
                try { release.invoke(rp) } catch (_: Throwable) {}
            }
        }

        // Вариант 1: только cb (старый вызов — возвращал null)
        var streamBinder = tryOpenStream("cb_only") { rq ->
            writeStrongBinder.invoke(rq, qcarStubInstance)
        }
        // Вариант 2: inputId=0, cb (основная гипотеза)
        if (streamBinder == null) streamBinder = tryOpenStream("id=0,cb") { rq ->
            writeInt32.invoke(rq, 0); writeStrongBinder.invoke(rq, qcarStubInstance)
        }
        // Вариант 3: inputId=1, cb
        if (streamBinder == null) streamBinder = tryOpenStream("id=1,cb") { rq ->
            writeInt32.invoke(rq, 1); writeStrongBinder.invoke(rq, qcarStubInstance)
        }
        // Вариант 4: inputId=0 без cb (HAL принял без cb ранее, вернул handle=2)
        if (streamBinder == null) streamBinder = tryOpenStream("id=0,no_cb") { rq ->
            writeInt32.invoke(rq, 0)
        }
        // Вариант 5: inputId=0, cb=null (writeStrongBinder null)
        if (streamBinder == null) streamBinder = tryOpenStream("id=0,cb=null") { rq ->
            writeInt32.invoke(rq, 0); writeStrongBinder.invoke(rq, null)
        }

        Thread.sleep(300)
        sb.appendLine("  lastCode@300ms (post-openStream): ${try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }}")

        val sb2 = streamBinder
        if (sb2 != null) try {
            sb.appendLine("\n  *** IQcarCameraStream ПОЛУЧЕН — начинаем поток ***")

            // Вывести все методы HwParcel (для понимания доступного API)
            val hwParcelMethodNames = hwParcelClass.methods
                .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
                .filter { m -> listOf("write","read","send","Buffer","Handle","blob","Blob").any { m.contains(it, ignoreCase=true) } }
                .sorted()
            sb.appendLine("  HwParcel I/O methods: $hwParcelMethodNames")

            // HwBlob — для передачи структур через HIDL
            val hwBlobClass = try { Class.forName("android.os.HwBlob") } catch (_: Throwable) { null }
            val hwBlobCtor  = try { hwBlobClass?.getConstructor(Int::class.java) } catch (_: Throwable) { null }
            val writeBuffer = try { if (hwBlobClass != null) hwParcelClass.getMethod("writeBuffer", hwBlobClass) else null } catch (_: Throwable) { null }
            val putInt32B   = try { hwBlobClass?.getMethod("putInt32", Long::class.java, Int::class.java) } catch (_: Throwable) { null }
            val putInt64B   = try { hwBlobClass?.getMethod("putInt64", Long::class.java, Long::class.java) } catch (_: Throwable) { null }
            sb.appendLine("  HwBlob: ${if (hwBlobCtor != null) "OK" else "unavailable"}, writeBuffer: ${if (writeBuffer != null) "OK" else "unavailable"}")

            // selfTransact убран: HwBinder.transact() без нативного контекста → SIGSEGV

            // Get transact method from stream binder's class
            val streamTransact = try {
                sb2.javaClass.getMethod(
                    "transact", Int::class.java, hwParcelClass, hwParcelClass, Int::class.java)
            } catch (_: Throwable) { transact }

            // Helper: bidirectional call on the stream binder
            fun streamCall(code: Int, label: String, writeArgs: ((Any) -> Unit)? = null) {
                val rq = newParcel(); val rp = newParcel()
                try {
                    writeToken.invoke(rq, streamIfaceToken)
                    writeArgs?.invoke(rq)
                    streamTransact.invoke(sb2, code, rq, rp, 0)
                    try { verifySuc.invoke(rp) } catch (_: Throwable) {}
                    val v = try { readInt32.invoke(rp) as? Int } catch (_: Throwable) { null }
                    sb.appendLine("  stream.method=$code($label) status=$v")
                } catch (e: Throwable) {
                    val inner = e.cause ?: e
                    sb.appendLine("  stream.method=$code($label): ${inner.javaClass.simpleName}: ${inner.message?.take(80)}")
                } finally {
                    try { release.invoke(rq) } catch (_: Throwable) {}
                    try { release.invoke(rp) } catch (_: Throwable) {}
                }
            }

            // Helper: ONEWAY call on the stream binder
            fun streamOW(code: Int, label: String, writeArgs: ((Any) -> Unit)? = null) {
                val rq = newParcel(); val rp = newParcel()
                try {
                    writeToken.invoke(rq, streamIfaceToken)
                    writeArgs?.invoke(rq)
                    streamTransact.invoke(sb2, code, rq, rp, 1)
                    sb.appendLine("  stream.method=$code($label ONEWAY): sent OK")
                } catch (e: Throwable) {
                    val inner = e.cause ?: e
                    sb.appendLine("  stream.method=$code($label ONEWAY): ${inner.javaClass.simpleName}: ${inner.message?.take(80)}")
                } finally {
                    try { release.invoke(rq) } catch (_: Throwable) {}
                    try { release.invoke(rp) } catch (_: Throwable) {}
                }
            }

            // ── configureStream probe ──
            sb.appendLine("\n  --- configureStream probe ---")
            if (hwBlobCtor != null && writeBuffer != null && putInt32B != null) {
                // Тест 0: проверяем работоспособность HwBlob (hidden API restriction?)
                try {
                    val testBlob = hwBlobCtor.newInstance(4)
                    val putOk = try { putInt32B.invoke(testBlob, 0L, 0x12345678); "OK" }
                        catch (t: Throwable) { "FAIL(${(t.cause?:t).javaClass.simpleName})" }
                    val tq = newParcel()
                    try {
                        writeToken.invoke(tq, streamIfaceToken)
                        val wbOk = try { writeBuffer.invoke(tq, testBlob); "OK" }
                            catch (t: Throwable) { "FAIL(${(t.cause?:t).javaClass.simpleName}:${(t.cause?:t).message?.take(40)})" }
                        sb.appendLine("  HwBlob(sz=4): putInt32=$putOk writeBuffer=$wbOk")
                    } finally { try { release.invoke(tq) } catch (_: Throwable) {} }
                } catch (t: Throwable) {
                    sb.appendLine("  HwBlob create FAIL: ${t.javaClass.simpleName}")
                }

                // Тесты: разные размеры/форматы QcarcamStreamConfig
                // Форматы: 0=UYVY_8, 1=UYVY_10, 3=RGB888, 5=NV12 (QTI automotive)
                data class CfgAttempt(val sz: Int, val fields: List<Int>, val label: String)
                // QcarcamStreamConfig field order guesses (QTI documentation patterns):
                // A: [opmode, numBufs, colorFmt, stride, width, height, flags]
                // B: [width, height, colorFmt, numBufs, stride, opmode, flags]
                // C: [colorFmt, width, height, numBufs, ...]
                // opmode: 0=single, 1=continuous; numBufs: 3-4; colorFmt: 0=UYVY_8, 12=NV12
                val attempts = listOf(
                    // Layout A: opmode, numBufs, fmt, stride, width, height, flags
                    CfgAttempt(28, listOf(1, 3, 0, 1280, 1280, 720, 0),   "A:cont,3,uyvy,1280,1280,720"),
                    CfgAttempt(28, listOf(1, 3, 12, 1280, 1280, 720, 0),  "A:cont,3,nv12,1280,1280,720"),
                    CfgAttempt(28, listOf(1, 4, 0, 1280, 1280, 720, 0),   "A:cont,4,uyvy,1280,1280,720"),
                    CfgAttempt(28, listOf(0, 3, 0, 1280, 1280, 720, 0),   "A:single,3,uyvy"),
                    // Layout B: width, height, fmt, numBufs, stride, opmode, flags
                    CfgAttempt(28, listOf(1280, 720, 0, 3, 1280, 1, 0),   "B:1280,720,uyvy,3"),
                    CfgAttempt(28, listOf(1280, 720, 12, 3, 1280, 1, 0),  "B:1280,720,nv12,3"),
                    // Layout C: fmt, width, height, numBufs (minimal, 16 bytes)
                    CfgAttempt(16, listOf(0, 1280, 720, 3),               "C:uyvy,1280,720,3"),
                    CfgAttempt(16, listOf(12, 1280, 720, 3),              "C:nv12,1280,720,3"),
                    // Layout D: numBufs first
                    CfgAttempt(20, listOf(3, 0, 1280, 720, 0),            "D:3,uyvy,1280,720"),
                    CfgAttempt(20, listOf(3, 12, 1280, 720, 0),           "D:3,nv12,1280,720"),
                    // inputId-based: stream config starts with inputId=0
                    CfgAttempt(32, listOf(0, 1, 3, 0, 1280, 720, 1280, 0),"E:id=0,cont,3,uyvy"),
                    CfgAttempt(32, listOf(0, 1280, 720, 0, 3, 1, 1280, 0),"E:id=0,1280,720"),
                    // All zeros to see if HAL responds to anything
                    CfgAttempt(28, listOf(0, 0, 0, 0, 0, 0, 0),           "allzeros"),
                )
                // ── ИСПРАВЛЕНИЕ: configureStream — ONEWAY метод (flags=1) ──
                // Ранее вызывали с flags=0 → ядро возвращало EINVAL до достижения сервера.
                // ONEWAY = fire-and-forget, reply не нужен, ждём callback после вызова.

                fun cfgOneway(label: String, writeArgs: (Any) -> Unit) {
                    val rq = newParcel(); val rp = newParcel()
                    try {
                        writeToken.invoke(rq, streamIfaceToken)
                        writeArgs(rq)
                        streamTransact.invoke(sb2, 2, rq, rp, 1)  // flags=1 ONEWAY
                        sb.appendLine("  cfg_OW($label): sent OK")
                    } catch (e: Throwable) {
                        sb.appendLine("  cfg_OW($label): ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message?.take(50)}")
                    } finally {
                        try { release.invoke(rq) } catch (_: Throwable) {}
                        try { release.invoke(rp) } catch (_: Throwable) {}
                    }
                }

                // Тест A: совсем без аргументов — посмотрим упадёт ли сервер
                cfgOneway("no_args") {}

                Thread.sleep(300)
                sb.appendLine("  lastCode after cfg_OW(no_args)+300ms: ${try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }}")

                // Тест B: с HwBlob разных размеров (ONEWAY)
                for (att in attempts) {
                    Thread.sleep(50)
                    val rq = newParcel(); val rp = newParcel()
                    try {
                        writeToken.invoke(rq, streamIfaceToken)
                        val blob = hwBlobCtor.newInstance(att.sz)
                        att.fields.forEachIndexed { i, v ->
                            try { putInt32B.invoke(blob, (i * 4).toLong(), v) } catch (_: Throwable) {}
                        }
                        try { writeBuffer.invoke(rq, blob) }
                        catch (wbEx: Throwable) {
                            sb.appendLine("  cfg_OW(${att.label}) wbFAIL: ${(wbEx.cause?:wbEx).javaClass.simpleName}")
                            continue
                        }
                        streamTransact.invoke(sb2, 2, rq, rp, 1)  // ONEWAY
                        sb.appendLine("  cfg_OW(${att.label}): sent OK")
                    } catch (e: Throwable) {
                        sb.appendLine("  cfg_OW(${att.label}): ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message?.take(40)}")
                    } finally {
                        try { release.invoke(rq) } catch (_: Throwable) {}
                        try { release.invoke(rp) } catch (_: Throwable) {}
                    }
                }

                // Тест C: плоские int32 (ONEWAY)
                for (fmt in listOf(0, 1, 3)) {
                    val rq = newParcel(); val rp = newParcel()
                    try {
                        writeToken.invoke(rq, streamIfaceToken)
                        writeInt32.invoke(rq, fmt); writeInt32.invoke(rq, 1280)
                        writeInt32.invoke(rq, 720); writeInt32.invoke(rq, 3)
                        writeInt32.invoke(rq, 0);   writeInt32.invoke(rq, 0)
                        streamTransact.invoke(sb2, 2, rq, rp, 1)  // ONEWAY
                        sb.appendLine("  cfg_OW(flat fmt=$fmt): sent OK")
                    } catch (e: Throwable) {
                        sb.appendLine("  cfg_OW(flat fmt=$fmt): ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message?.take(40)}")
                    } finally {
                        try { release.invoke(rq) } catch (_: Throwable) {}
                        try { release.invoke(rp) } catch (_: Throwable) {}
                    }
                }

            } else {
                // Нет HwBlob — пробуем только ONEWAY без аргументов
                val rq = newParcel(); val rp = newParcel()
                try {
                    writeToken.invoke(rq, streamIfaceToken)
                    streamTransact.invoke(sb2, 2, rq, rp, 1)
                    sb.appendLine("  cfg_OW(no args, no blob): sent OK")
                } catch (e: Throwable) {
                    sb.appendLine("  cfg_OW(no args, no blob): ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message?.take(50)}")
                } finally {
                    try { release.invoke(rq) } catch (_: Throwable) {}
                    try { release.invoke(rp) } catch (_: Throwable) {}
                }
            }

            // getStreamConfig после ONEWAY configure — теперь должен вернуть данные
            Thread.sleep(300)
            streamCall(1, "getStreamConfig after cfg_OW")

            // startStream сразу после configure (без setStreamBuffers — посмотрим реакцию)
            streamOW(4, "startStream (after cfg_OW)")

            // Ждём callback — теперь configureStream дошёл до HAL
            Thread.sleep(1200)
            sb.appendLine("  lastCode after cfg_OW+startStream+1200ms: ${try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }}")

            // getFrame / releaseFrame
            streamCall(7, "getFrame")
            streamCall(8, "releaseFrame(no args)")

            // stopStream
            streamOW(5, "stopStream")

            // Закрываем стрим на IQcarCamera (метод 3 = closeStream, принимает IQcarCameraStream)
            Thread.sleep(200)
            val rqClose = newParcel(); val rpClose = newParcel()
            try {
                writeToken.invoke(rqClose, iface)
                writeStrongBinder.invoke(rqClose, sb2)   // передаём stream binder как аргумент
                transact.invoke(binder, 3, rqClose, rpClose, 0)  // m=3 closeStream на IQcarCamera
                try { verifySuc.invoke(rpClose) } catch (_: Throwable) {}
                sb.appendLine("  closeStream: OK")
            } catch (e: Throwable) {
                sb.appendLine("  closeStream: ${(e.cause ?: e).javaClass.simpleName}")
            } finally {
                try { release.invoke(rqClose) } catch (_: Throwable) {}
                try { release.invoke(rpClose) } catch (_: Throwable) {}
            }

            // Финальная проверка callback
            Thread.sleep(300)
            val lastFinal = try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }
            sb.appendLine("  lastCode FINAL: $lastFinal${if ((lastFinal as? Int) != null && lastFinal != -1) " *** CALLBACK RECEIVED code=$lastFinal ***" else ""}")

        } catch (e: Throwable) {
            sb.appendLine("  *** STREAM BLOCK CRASHED: ${e.javaClass.name}: ${e.message?.take(100)} ***")

        } else {
            sb.appendLine("  IQcarCameraStream binder = null")
            sb.appendLine("  → openStream reply не содержит HwBinder объект")
            sb.appendLine("  → Возможно openStream требует другие аргументы (inputId?)")
            // Diagnostic: show what readInt32 gives us
            val (raw, rawBytes) = safeTransact(2) { req -> writeStrongBinder.invoke(req, qcarStubInstance) }
            sb.appendLine("  openStream int32=$raw (0x${raw?.toUInt()?.toString(16)}) reply=${rawBytes.take(8).map{"0x${it.toUInt().toString(16)}"}}")
            Thread.sleep(3000)
            val lastFinal = try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }
            sb.appendLine("  lastCode after 3s = $lastFinal")
        }

        // ── getInputStreamList: читаем через readBuffer ──
        // ВАЖНО: не читаем int32 до readBuffer — иначе позиция смещается
        sb.appendLine("\n  --- getInputStreamList readBuffer ---")
        try {
            val readBufM = hwParcelClass.methods.firstOrNull { it.name == "readBuffer" && it.parameterCount >= 1 }
            val readEmbM = hwParcelClass.methods.firstOrNull { it.name == "readEmbeddedBuffer" && it.parameterCount >= 1 }
            sb.appendLine("  readBuffer:         ${readBufM?.let { "${it.name}(${it.parameterTypes.map{p->p.simpleName}})" } ?: "not found"}")
            sb.appendLine("  readEmbeddedBuffer: ${readEmbM?.let { "${it.name}(${it.parameterTypes.map{p->p.simpleName}})" } ?: "not found"}")

            if (readBufM != null) {
                val hwBlobCls2 = try { Class.forName("android.os.HwBlob") } catch (_: Throwable) { null }
                // MUST use javaPrimitiveType — HwBlob.getInt32/64 take primitive long, not boxed Long
                val blobGetI32 = try { hwBlobCls2?.getMethod("getInt32",  Long::class.javaPrimitiveType) } catch (_: Throwable) { null }
                val blobGetI64 = try { hwBlobCls2?.getMethod("getInt64",  Long::class.javaPrimitiveType) } catch (_: Throwable) { null }
                fun blobI32(blob: Any, off: Long): Int?  = try { blobGetI32?.invoke(blob, off) as? Int  } catch (_: Throwable) { null }
                fun blobI64(blob: Any, off: Long): Long? = try { blobGetI64?.invoke(blob, off) as? Long } catch (_: Throwable) { null }

                val rq2 = newParcel(); val rp2 = newParcel()
                try {
                    writeToken.invoke(rq2, iface)
                    transact.invoke(binder, 1, rq2, rp2, 0)
                    try { verifySuc.invoke(rp2) } catch (_: Throwable) {}

                    // ВАЖНО: сначала читаем Error enum (int32), потом readBuffer для hidl_vec
                    val errCode = try { readInt32.invoke(rp2) as? Int } catch (_: Throwable) { null }
                    sb.appendLine("  getInputStreamList Error=$errCode")

                    val rbParams = readBufM.parameterTypes
                    // hidl_vec<T> header = 16 bytes: uint64 ptr + uint64 count
                    val vecBlob = when (rbParams.size) {
                        1    -> try { readBufM.invoke(rp2, 16L) } catch (t: Throwable) {
                            sb.appendLine("  readBuffer(16) FAIL: ${(t.cause?:t).javaClass.simpleName}: ${(t.cause?:t).message?.take(60)}")
                            null
                        }
                        2    -> try { readBufM.invoke(rp2, 16L, LongArray(1)) } catch (t: Throwable) {
                            sb.appendLine("  readBuffer(16,handle) FAIL: ${(t.cause?:t).javaClass.simpleName}: ${(t.cause?:t).message?.take(60)}")
                            null
                        }
                        else -> null.also { sb.appendLine("  readBuffer params=${rbParams.map{it.simpleName}}") }
                    }
                    sb.appendLine("  readBuffer(16) → ${if (vecBlob == null) "null" else vecBlob.javaClass.simpleName}")
                    sb.appendLine("  blobGetI32=${blobGetI32 != null} blobGetI64=${blobGetI64 != null}")

                    if (vecBlob != null && hwBlobCls2?.isInstance(vecBlob) == true) {
                        // Dump all 4 int32 fields to reveal actual struct layout
                        val b0 = blobI32(vecBlob, 0L); val b4 = blobI32(vecBlob, 4L)
                        val b8 = blobI32(vecBlob, 8L); val b12 = blobI32(vecBlob, 12L)
                        sb.appendLine("  vec[0]=${b0?.toUInt()} [4]=${b4?.toUInt()} [8]=${b8?.toUInt()} [12]=${b12?.toUInt()}")

                        // hidl_vec: ptr(uint64 at 0) + count(uint64 at 8)
                        val count64 = blobI64(vecBlob, 8L) ?: blobI32(vecBlob, 8L)?.toLong()
                        val count = count64?.toInt() ?: 0
                        sb.appendLine("  hidl_vec count=$count")

                        // Try readEmbeddedBuffer UNCONDITIONALLY — raw hex shows 0x480=1152 bytes always
                        if (readEmbM != null) {
                            val embParams = readEmbM.parameterTypes
                            val knownEmbSz = 1152L
                            val eblob = try {
                                when (embParams.size) {
                                    4 -> readEmbM.invoke(rp2, knownEmbSz, 0L, 0L, false)
                                    3 -> readEmbM.invoke(rp2, knownEmbSz, 0L, 0L)
                                    else -> null
                                }
                            } catch (t: Throwable) {
                                sb.appendLine("  readEmbedded(1152) FAIL: ${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message?.take(60)}")
                                null
                            }
                            if (eblob != null && hwBlobCls2.isInstance(eblob)) {
                                sb.appendLine("  readEmbedded(1152) → OK")
                                // Dump first 288 bytes (72 uint32s, 9 rows × 8 values)
                                val hexVals = (0 until 72).map { i ->
                                    blobI32(eblob, i * 4L)?.toUInt()?.toString(16)?.padStart(8, '0') ?: "????????"
                                }
                                for (row in 0 until 9) {
                                    val vals = hexVals.subList(row * 8, row * 8 + 8).joinToString(" ")
                                    sb.appendLine("  emb[${row * 32}+]: $vals")
                                }
                                // Structured dump if count known
                                if (count > 0) {
                                    val elemSz = knownEmbSz / count
                                    sb.appendLine("  ($count elems × $elemSz bytes)")
                                    for (elem in 0 until minOf(count, 4)) {
                                        val base = elem * elemSz
                                        val nFields = minOf(16L, elemSz / 4).toInt()
                                        val fields = (0 until nFields).map { i ->
                                            blobI32(eblob, base + i * 4L)?.toUInt()?.toString(16) ?: "?"
                                        }
                                        sb.appendLine("  elem[$elem]: ${fields.joinToString(" ")}")
                                    }
                                }
                            }
                        }
                    } else if (vecBlob == null && errCode == 0) {
                        sb.appendLine("  fallback: raw int32 reads after Error:")
                        val raws = (0 until 20).mapNotNull {
                            try { readInt32.invoke(rp2) as? Int } catch (_: Throwable) { null }
                        }
                        sb.appendLine("  ${raws.map { "0x${it.toUInt().toString(16)}" }}")
                    }
                } finally {
                    try { release.invoke(rq2) } catch (_: Throwable) {}
                    try { release.invoke(rp2) } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            sb.appendLine("  readBuffer probe: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message?.take(60)}")
        }

        // Logcat for any HAL messages
        val lcR = shell("logcat", "-d", "-t", "300", "-v", "brief", timeoutMs = 5000)
        val qcarLines = lcR.stdout.lines().filter { line ->
            listOf("qcar", "QCar", "qcarcam", "hwbinder", "HwBinder", "vendor.qti.automotive",
                   "HardwareBuffer", "NativeHandle")
                .any { line.contains(it) }
        }.takeLast(40)
        if (qcarLines.isNotEmpty()) {
            sb.appendLine("\n  --- logcat ---")
            qcarLines.forEach { sb.appendLine("  ${it.take(200)}") }
        }
    }

}


internal fun DiagnosticsEngine.checkQCarCamDeeper(): CheckResult {
    val sb = StringBuilder()
    val libPath = "/vendor/lib64/vendor.qti.automotive.qcarcam@1.0.so"

    // 1. nm --demangle to get exact HIDL method names
    sb.appendLine("[1] nm --demangle $libPath")
    val nmR = shell("nm", "-D", "--demangle", libPath, timeoutMs = 10000)
    if (nmR.stdout.isNotEmpty()) {
        val methods = nmR.stdout.lines()
            .filter { line ->
                listOf("BpHwQcarCamera", "BnHwQcarCamera", "onTransact",
                       "openSession", "setBuffers", "getInputList", "start", "stop")
                    .any { line.contains(it) }
            }
            .map { line ->
                val parts = line.trim().split(Regex("\\s+"), 3)
                val sym = parts.getOrElse(2) { line }
                sym.replace("vendor::qti::automotive::qcarcam::V1_0::", "")
                   .replace("android::sp<", "sp<")
                   .replace("android::hardware::Return<", "Return<")
                   .replace("android::hardware::hidl_vec<", "vec<")
                   .take(180)
            }
            .distinct().take(40)
        if (methods.isNotEmpty()) {
            sb.appendLine("Найдены методы (${methods.size}):")
            methods.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("nm OK (${nmR.stdout.lines().size} строк), но BpHw/BnHw не найдены")
            // Show raw first 10 lines as sample
            nmR.stdout.lines().take(10).forEach { sb.appendLine("  ${it.take(120)}") }
        }
    } else {
        sb.appendLine("nm failed (exit=${nmR.exitCode}): ${nmR.stderr.take(100)}")
        // Fallback: try objdump
        val odR = shell("objdump", "-T", libPath, timeoutMs = 10000)
        val odLines = odR.stdout.lines()
            .filter { it.contains("QcarCamera") || it.contains("QcarStream") }
            .take(30)
        if (odLines.isNotEmpty()) {
            sb.appendLine("objdump fallback:")
            odLines.forEach { sb.appendLine("  ${it.take(160)}") }
        } else {
            sb.appendLine("objdump также ничего: ${odR.stderr.take(60)}")
        }
    }

    // 2. Search ALL permission XML files for CAMERA → GID mapping
    sb.appendLine("\n[2] Permission files CAMERA→GID mapping (все директории)")
    val permDirs = listOf(
        "/system/etc/permissions",
        "/vendor/etc/permissions",
        "/product/etc/permissions",
        "/odm/etc/permissions",
        "/system_ext/etc/permissions"
    )
    var foundCameraMapping = false
    for (dir in permDirs) {
        val dirFile = java.io.File(dir)
        if (!dirFile.exists()) { sb.appendLine("  $dir: нет такой директории"); continue }
        val xmlFiles = dirFile.listFiles { f -> f.name.endsWith(".xml") } ?: emptyArray()
        sb.appendLine("  $dir (${xmlFiles.size} xml файлов):")
        for (f in xmlFiles) {
            try {
                val txt = f.readText()
                if (txt.contains("CAMERA", ignoreCase = true) || txt.contains("1006")) {
                    sb.appendLine("    *** ${f.name} содержит CAMERA или 1006 ***")
                    // Show relevant snippet
                    val idx = txt.indexOfFirst { _ ->
                        txt.contains("CAMERA", ignoreCase = true)
                    }.let { txt.indexOf("CAMERA", ignoreCase = true) }
                    if (idx >= 0) {
                        val snip = txt.substring(maxOf(0, idx - 40), minOf(txt.length, idx + 250))
                        sb.appendLine("    $snip")
                    }
                    foundCameraMapping = true
                }
            } catch (_: Exception) {}
        }
    }
    if (!foundCameraMapping) {
        sb.appendLine("  CAMERA → GID маппинг НЕ НАЙДЕН НИ В ОДНОМ permission XML!")
        sb.appendLine("  → GID 1006 недоступен через pm grant — нужен другой путь")
    }

    // 3. Native libs inside oneOS_XCParking (parking/AVM app)
    sb.appendLine("\n[3] Native libs oneOS_XCParking")
    val parkingApk = "/system/app/oneOS_XCParking/oneOS_XCParking.apk"
    val uzR = shell("unzip", "-l", parkingApk, timeoutMs = 5000)
    val soLines = uzR.stdout.lines().filter { it.contains(".so") }.take(30)
    if (soLines.isNotEmpty()) {
        soLines.forEach { sb.appendLine("  ${it.trim()}") }
    } else {
        sb.appendLine("unzip failed или нет .so: ${uzR.stderr.take(80)}")
        // Try find
        val fR = shell("find", "/system/app/oneOS_XCParking", "-name", "*.so", timeoutMs = 3000)
        if (fR.stdout.isNotEmpty()) fR.stdout.lines().take(20).forEach { sb.appendLine("  $it") }
        else sb.appendLine("find: ${fR.stdout.take(60)}")
    }

    // Also check lib dirs for relevant native libraries
    sb.appendLine("\n[3b] /vendor/lib64/ qcarcam/avm libs")
    val vendorLibR = shell("find", "/vendor/lib64", "-name", "*qcarcam*", "-o", "-name", "*avm*", "-o", "-name", "*surround*", timeoutMs = 4000)
    val vendorSo = (vendorLibR.stdout.lines() + run {
        val r2 = shell("find", "/vendor/lib", "-name", "*qcarcam*", "-o", "-name", "*avm*", timeoutMs = 3000)
        r2.stdout.lines()
    }).filter { it.isNotBlank() }.take(30)
    if (vendorSo.isNotEmpty()) vendorSo.forEach { sb.appendLine("  $it") }
    else sb.appendLine("Не найдено qcarcam/avm/surround в /vendor/lib*")

    // 4. All running processes — broader scan for camera/avm renderers
    sb.appendLine("\n[4] Процессы с camera/avm/surround/360 в cmdline")
    val allPids = java.io.File("/proc").listFiles()
        ?.filter { it.name.all { c -> c.isDigit() } } ?: emptyList()
    val camProcs = mutableListOf<String>()
    for (pidFile in allPids.take(300)) {
        try {
            val cmdline = java.io.File("${pidFile.path}/cmdline").readText()
                .replace(' ', ' ').trim()
            if (listOf("camera", "avm", "surround", "360", "qcar", "parking", "xcpark")
                    .any { cmdline.contains(it, true) }) {
                camProcs += "  pid=${pidFile.name}: $cmdline"
            }
        } catch (_: Exception) {}
    }
    if (camProcs.isNotEmpty()) camProcs.forEach { sb.appendLine(it) }
    else sb.appendLine("Нет camera/avm процессов в /proc/*/cmdline")

    // 5. Check if our process has camera GID now (after any permission grants)
    sb.appendLine("\n[5] Текущие GID нашего процесса")
    try {
        val status = java.io.File("/proc/self/status").readText()
        val gidLines = status.lines().filter { it.startsWith("Uid:") || it.startsWith("Gid:") ||
            it.startsWith("Groups:") }
        gidLines.forEach { sb.appendLine("  $it") }
        val groups = status.lines().firstOrNull { it.startsWith("Groups:") }
        if (groups?.contains("1006") == true)
            sb.appendLine("  *** GID 1006 (camera) ЕСТЬ! ***")
        else
            sb.appendLine("  GID 1006 (camera) ОТСУТСТВУЕТ")
    } catch (e: Exception) {
        sb.appendLine("  /proc/self/status: ${e.message}")
    }

    // 6. strings on QcarCam .so — looking for struct field names and format strings
    sb.appendLine("\n[6] strings $libPath (stream config keywords)")
    val strR = shell("strings", libPath, timeoutMs = 10000)
    if (strR.stdout.isNotEmpty()) {
        val interesting = strR.stdout.lines().filter { line ->
            line.length in 4..80 && listOf(
                "InputId", "inputId", "input_id", "colorFmt", "color_fmt", "ColorFmt",
                "width", "height", "stride", "numBufs", "num_bufs", "numBuffers",
                "UYVY", "NV12", "YUV420", "YUYV", "format", "Format",
                "qcarcam_config", "QcarcamStream", "stream_config",
                "open_stream", "openStream", "configureStream",
                "getInputStreamList", "getInputList"
            ).any { line.contains(it) }
        }.distinct().take(40)
        if (interesting.isNotEmpty()) {
            sb.appendLine("  ${interesting.size} совпадений:")
            interesting.forEach { sb.appendLine("  | $it") }
        } else {
            sb.appendLine("  strings OK (${strR.stdout.lines().size} строк), keywords не найдены")
        }
    } else {
        sb.appendLine("  strings failed: ${strR.stderr.take(80)}")
    }

    // 7. QcarCam XML config — usually has input stream IDs, formats, resolutions
    sb.appendLine("\n[7] QcarCam config XML")
    val xmlCandidates = listOf(
        "/vendor/etc/qcarcam_config.xml",
        "/vendor/etc/camera/qcarcam_config.xml",
        "/vendor/etc/qcarcam/qcarcam_config.xml"
    )
    var foundXml = false
    for (path in xmlCandidates) {
        val f = java.io.File(path)
        if (f.exists()) {
            try {
                val txt = f.readText()
                sb.appendLine("  FOUND: $path (${txt.length} bytes)")
                txt.lines().take(60).forEach { sb.appendLine("  $it") }
                foundXml = true
            } catch (e: Exception) {
                sb.appendLine("  $path: exists but read failed: ${e.message?.take(60)}")
            }
            break
        }
    }
    if (!foundXml) {
        // Broader scan
        val scanR = shell("find", "/vendor/etc", "-name", "*qcarcam*", "-o", "-name", "*qcar_cam*",
            timeoutMs = 5000)
        if (scanR.stdout.isNotEmpty()) {
            sb.appendLine("  find /vendor/etc: ${scanR.stdout}")
            scanR.stdout.lines().filter { it.endsWith(".xml") }.firstOrNull()?.let { xmlPath ->
                val txt = try { java.io.File(xmlPath).readText() } catch (_: Exception) { "" }
                if (txt.isNotEmpty()) {
                    sb.appendLine("  Content of $xmlPath:")
                    txt.lines().take(60).forEach { sb.appendLine("  $it") }
                    foundXml = true
                }
            }
        }
        if (!foundXml) sb.appendLine("  qcarcam_config.xml не найден")
    }

    return CheckResult("QCarCam Deeper", CheckStatus.INFO, sb.toString().trim())
}

