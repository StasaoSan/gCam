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

    // joinRpcThreadpool() blocks the calling thread in the HwBinder event loop.
    // Without it, the HAL cannot deliver callbacks to our process.
    val hwBinderCls2 = try { Class.forName("android.os.HwBinder") } catch (_: Throwable) { null }
    val hwBrThread = if (hwBinderCls2 != null) {
        Thread {
            try {
                hwBinderCls2.getMethod("configureRpcThreadpool", Long::class.java, Boolean::class.java)
                    .invoke(null, 1L, true)  // 1 thread, callerWillJoin=true
                hwBinderCls2.getMethod("joinRpcThreadpool").invoke(null)  // blocks
            } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.name = "qcarcam-hwbr"; it.start() }
    } else null
    Thread.sleep(300)  // wait for event loop to initialise
    sb.appendLine("  HwBinder callback thread: ${when { hwBrThread == null -> "HwBinder class not found"; hwBrThread.isAlive -> "alive (callbacks ready)"; else -> "DIED — callbacks won't work" }}")

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
        if (sb2 != null) {
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

            // Самотест: вызвать наш собственный callback через transact
            // Если lastCode обновится → thread pool работает, HAL просто не вызывает нас
            sb.appendLine("\n  --- Самотест callback ---")
            val selfTransact = try {
                qcarStubInstance!!.javaClass.getMethod("transact",
                    Int::class.java, hwParcelClass, hwParcelClass, Int::class.java)
            } catch (_: Throwable) { null }
            if (selfTransact != null && getLastCode != null) {
                val tq = newParcel(); val tr = newParcel()
                try {
                    writeToken.invoke(tq, "vendor.qti.automotive.qcarcam@1.0::IQcarCameraStreamCB")
                    selfTransact.invoke(qcarStubInstance, 1, tq, tr, 0)
                    val lc = try { getLastCode.invoke(qcarStubInstance) } catch (_: Throwable) { -99 }
                    sb.appendLine("  selfTransact(code=1) → lastCode=$lc${if ((lc as? Int) == 1) " ✓ THREAD POOL WORKS" else " ✗ thread pool broken"}")
                } catch (e: Throwable) {
                    val inner = e.cause ?: e
                    sb.appendLine("  selfTransact ERROR: ${inner.javaClass.simpleName}: ${inner.message?.take(80)}")
                } finally {
                    try { release.invoke(tq) } catch (_: Throwable) {}
                    try { release.invoke(tr) } catch (_: Throwable) {}
                }
            } else {
                sb.appendLine("  selfTransact: not available (selfTransact=$selfTransact)")
            }

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

            // ── configureStream через HwBlob ──
            // HAL ожидает структуру QcarcamStreamConfig через HIDL buffer, не raw int32.
            // Пробуем разные размеры (нет .hal файла, угадываем layout).
            sb.appendLine("\n  --- configureStream HwBlob probe ---")
            if (hwBlobCtor != null && writeBuffer != null && putInt32B != null) {
                // Типичные поля: inputId, width, height, format, frameRate, flags = 6×4=24 bytes
                // Пробуем size=24 с разными шириной/высотой (типичные авто-камеры)
                data class CfgAttempt(val sz: Int, val fields: List<Int>, val label: String)
                val attempts = listOf(
                    CfgAttempt(24, listOf(0, 0, 0, 0, 0, 0), "all-zero sz=24"),
                    CfgAttempt(24, listOf(0, 1280, 720, 0, 0, 0), "1280x720 sz=24"),
                    CfgAttempt(24, listOf(0, 640, 480, 0, 0, 0),  "640x480 sz=24"),
                    CfgAttempt(16, listOf(0, 0, 0, 0), "all-zero sz=16"),
                    CfgAttempt(32, listOf(0, 1280, 720, 0, 0, 0, 0, 0), "1280x720 sz=32"),
                    CfgAttempt(8,  listOf(0, 0), "all-zero sz=8"),
                    CfgAttempt(48, List(12) { 0 }, "all-zero sz=48")
                )
                for (att in attempts) {
                    val rq = newParcel(); val rp = newParcel()
                    try {
                        writeToken.invoke(rq, streamIfaceToken)
                        val blob = hwBlobCtor.newInstance(att.sz)
                        att.fields.forEachIndexed { i, v ->
                            try { putInt32B.invoke(blob, (i * 4).toLong(), v) } catch (_: Throwable) {}
                        }
                        writeBuffer.invoke(rq, blob)
                        streamTransact.invoke(sb2, 2, rq, rp, 0)
                        try { verifySuc.invoke(rp) } catch (_: Throwable) {}
                        val v = try { readInt32.invoke(rp) as? Int } catch (_: Throwable) { null }
                        sb.appendLine("  configureStream(${att.label}) status=$v${if (v == 0) " ✓ OK!" else if (v != null && v == -61) " (ENODATA)" else ""}")
                    } catch (e: Throwable) {
                        val inner = e.cause ?: e
                        sb.appendLine("  configureStream(${att.label}): ${inner.javaClass.simpleName}")
                    } finally {
                        try { release.invoke(rq) } catch (_: Throwable) {}
                        try { release.invoke(rp) } catch (_: Throwable) {}
                    }
                }
            } else {
                sb.appendLine("  HwBlob недоступен — пробуем без blob:")
                streamCall(2, "configureStream(no args)")
            }

            // method=1: getStreamConfig (после попытки configure)
            streamCall(1, "getStreamConfig")

            // method=3: setStreamBuffers (ONEWAY) — пока без реальных буферов
            streamOW(3, "setStreamBuffers(empty)")

            // method=4: startStream (ONEWAY)
            streamOW(4, "startStream")

            // Ждём callback — 5 секунд
            Thread.sleep(5000)
            sb.appendLine("  lastCode after startStream+5s: ${try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }}")

            // method=7: getFrame
            streamCall(7, "getFrame")

            // method=8: releaseFrame
            streamCall(8, "releaseFrame(no args)")

            // method=5: stopStream (ONEWAY)
            streamOW(5, "stopStream")

            // Финальная проверка callback
            Thread.sleep(500)
            val lastFinal = try { getLastCode?.invoke(qcarStubInstance) } catch (_: Throwable) { null }
            sb.appendLine("  lastCode FINAL: $lastFinal${if ((lastFinal as? Int) != null && lastFinal != -1) " *** CALLBACK RECEIVED code=$lastFinal ***" else ""}")

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

        // Logcat for any HAL messages
        val lcR = shell("logcat", "-d", "-t", "400", "-v", "brief", timeoutMs = 5000)
        val qcarLines = lcR.stdout.lines().filter { line ->
            listOf("qcar", "QCar", "QCAR", "qcarcam", "QCarCam", "QCarCamera",
                   "hwbinder", "HwBinder", "vendor.qti.automotive")
                .any { line.contains(it) }
        }.takeLast(50)
        if (qcarLines.isNotEmpty()) {
            sb.appendLine("\n  --- logcat (qcarcam/hwbinder) ---")
            qcarLines.forEach { sb.appendLine("  ${it.take(200)}") }
        }
    }

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

    // strings on vendor lib — look for ALL method/interface names
    val libPath = "/vendor/lib64/vendor.qti.automotive.qcarcam@1.0.so"
    val strR = shell("strings", libPath, timeoutMs = 5000)
    if (strR.stdout.isNotEmpty()) {
        val allLines = strR.stdout.lines().filter { it.length in 4..120 }
        // Group 1: mangled C++ symbols with method names
        val mangledMethods = allLines.filter { it.startsWith("_Z") &&
            (it.contains("Session") || it.contains("Buffer") || it.contains("Stream") ||
             it.contains("Input") || it.contains("Start") || it.contains("Stop") ||
             it.contains("Frame") || it.contains("Surface") || it.contains("Alloc") ||
             it.contains("Queue") || it.contains("Config") || it.contains("Param") ||
             it.contains("Qcar") || it.contains("Camera")) }
        // Group 2: readable strings (method names, HIDL descriptors, error messages)
        val readable = allLines.filter { !it.startsWith("_Z") }.filter { line ->
            listOf("QcarCam", "IQcar", "session", "Session", "input", "frame", "stream",
                "Stream", "open", "start", "stop", "init", "buffer", "Buffer", "surface",
                "Surface", "alloc", "Alloc", "vendor.qti", "format", "width", "height",
                "queue", "param", "config", "method", "transact", "Camera", "camera")
                .any { line.contains(it) }
        }
        sb.appendLine("\nstrings $libPath (${allLines.size} строк всего):")
        if (mangledMethods.isNotEmpty()) {
            sb.appendLine("  Mangled symbols с интересными именами (${mangledMethods.size}):")
            mangledMethods.distinct().take(40).forEach { sb.appendLine("    $it") }
        }
        if (readable.isNotEmpty()) {
            sb.appendLine("  Читаемые строки (${readable.size}):")
            readable.distinct().take(40).forEach { sb.appendLine("    $it") }
        }
        if (mangledMethods.isEmpty() && readable.isEmpty()) {
            sb.appendLine("  Нет совпадений. Первые 20 строк:")
            allLines.take(20).forEach { sb.appendLine("    $it") }
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

internal fun DiagnosticsEngine.checkEvsHidl(): CheckResult {
    val sb = StringBuilder()

    val hwBinderCls = try {
        Class.forName("android.os.HwBinder")
    } catch (e: Exception) {
        return CheckResult("EVS HIDL", CheckStatus.FAIL, "HwBinder недоступен: ${e.message}")
    }
    val getServiceM = hwBinderCls.getMethod("getService", String::class.java, String::class.java)
    val hwParcelCls = try { Class.forName("android.os.HwParcel") } catch (e: Exception) {
        return CheckResult("EVS HIDL", CheckStatus.FAIL, "HwParcel недоступен: ${e.message}")
    }
    val newParcel   = { hwParcelCls.getDeclaredConstructor().newInstance() }
    val writeToken  = hwParcelCls.getMethod("writeInterfaceToken", String::class.java)
    val releaseP    = hwParcelCls.getMethod("release")
    val verifySuc  = hwParcelCls.getMethod("verifySuccess")
    val readInt32P  = hwParcelCls.getMethod("readInt32")

    // EVS Manager HIDL interfaces (running as android.automotive.evs.manager@1.1)
    val evsIfaces = listOf(
        "android.automotive.evs@1.1::IEvsEnumerator" to "default",
        "android.hardware.automotive.evs@1.1::IEvsEnumerator" to "default",
        "android.hardware.automotive.evs@1.0::IEvsEnumerator" to "default",
    )

    for ((iface, instance) in evsIfaces) {
        val binder = try {
            getServiceM.invoke(null, iface, instance)
        } catch (e: Exception) {
            sb.appendLine("$iface/$instance: err=${e.cause?.message?.take(60) ?: e.message?.take(60)}")
            continue
        }
        if (binder == null) {
            sb.appendLine("$iface/$instance: null")
            continue
        }
        sb.appendLine("$iface/$instance: FOUND! $binder")

        val transactM = try {
            binder.javaClass.getMethod("transact", Int::class.java, hwParcelCls, hwParcelCls, Int::class.java)
        } catch (e: Exception) {
            sb.appendLine("  transact method not found: ${e.message?.take(60)}")
            continue
        }

        fun evsTransact(code: Int, writeArgs: ((Any) -> Unit)? = null): Pair<Int?, List<Int>> {
            val req = newParcel()
            val rep = newParcel()
            return try {
                writeToken.invoke(req, iface)
                writeArgs?.invoke(req)
                transactM.invoke(binder, code, req, rep, 0)
                verifySuc.invoke(rep)
                val status = readInt32P.invoke(rep) as? Int
                val extra = mutableListOf<Int>()
                repeat(12) {
                    val v = try { readInt32P.invoke(rep) as? Int } catch (_: Throwable) { null } ?: return@repeat
                    extra.add(v)
                }
                Pair(status, extra)
            } catch (t: Throwable) {
                val inner = t.cause ?: t
                sb.appendLine("  EVS method=$code [${inner.javaClass.simpleName}]: ${inner.message?.take(70)}")
                Pair(null, emptyList())
            } finally {
                try { releaseP.invoke(req) } catch (_: Throwable) {}
                try { releaseP.invoke(rep) } catch (_: Throwable) {}
            }
        }

        // Probe methods 1–5 without args — EVS 1.0: method=1=getCameraList, method=3=openCamera
        for (m in 1..5) {
            val (s, d) = evsTransact(m)
            if (s != null) {
                val hex = d.map { "0x${it.toUInt().toString(16)}" }.joinToString(" ")
                sb.appendLine("  EVS method=$m status=$s hex=[$hex]")
            }
        }
    }

    // Also check regular Binder for EvsCameraService (ECarX EVS implementation)
    val smClass = try { Class.forName("android.os.ServiceManager") } catch (_: Exception) { null }
    val smGet   = smClass?.getMethod("getService", String::class.java)
    for (name in listOf("EvsCameraService", "evsCamera", "evs_manager", "automotive_evs")) {
        val b = try { smGet?.invoke(null, name) } catch (_: Exception) { null }
        sb.appendLine("ServiceManager(\"$name\"): ${if (b != null) "FOUND $b" else "null"}")
    }

    // lshal grep for evs
    val lsR = shell("lshal")
    val evsLines = lsR.stdout.lines().filter { it.contains("evs", true) }
    if (evsLines.isNotEmpty()) {
        sb.appendLine("\nlshal (evs):")
        evsLines.forEach { sb.appendLine("  $it") }
    } else {
        sb.appendLine("lshal evs: не найдено")
    }

    val hasEvs = sb.contains("FOUND")
    return CheckResult("EVS HIDL", if (hasEvs) CheckStatus.OK else CheckStatus.WARN, sb.toString().trim())
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

    return CheckResult("QCarCam Deeper", CheckStatus.INFO, sb.toString().trim())
}
