package com.stasao.gcam

import java.io.File

internal fun DiagnosticsEngine.checkCarAndEvsDetail(): CheckResult {
    val sb = StringBuilder()
    try {
        val carClass = Class.forName("android.car.Car")
        sb.appendLine("android.car.Car: доступен")

        val car = try {
            carClass.getMethod("createCar", android.content.Context::class.java).invoke(null, context)
        } catch (e: Exception) {
            return CheckResult("android.car Detail", CheckStatus.WARN, "Car.createCar() не удался: ${e.message}")
        }
        sb.appendLine("Car.createCar(): OK")

        val serviceNames = carClass.fields
            .filter { it.type == String::class.java }
            .mapNotNull { f -> try { f.get(null) as? String } catch (_: Exception) { null } }
            .filter { it.isNotEmpty() }

        val getManager = carClass.getMethod("getCarManager", String::class.java)
        val available = mutableListOf<String>()
        val unavailable = mutableListOf<String>()

        for (svcName in serviceNames) {
            try {
                val mgr = getManager.invoke(car, svcName)
                if (mgr != null) available.add("  + $svcName (${mgr.javaClass.simpleName})")
                else unavailable.add("  - $svcName")
            } catch (_: Exception) {
                unavailable.add("  ! $svcName")
            }
        }

        if (available.isNotEmpty()) {
            sb.appendLine("\nДоступные Car managers:")
            available.forEach { sb.appendLine(it) }
        }
        if (unavailable.isNotEmpty()) {
            sb.appendLine("\nНедоступные:")
            unavailable.take(8).forEach { sb.appendLine(it) }
            if (unavailable.size > 8) sb.appendLine("  ... ещё ${unavailable.size - 8}")
        }

        try { carClass.getMethod("disconnect").invoke(car) } catch (_: Exception) {}

    } catch (_: ClassNotFoundException) {
        return CheckResult("android.car Detail", CheckStatus.FAIL, "android.car.Car не найден — не AAOS устройство")
    } catch (e: Exception) {
        sb.appendLine("Ошибка: ${e.message}")
    }

    val status = if (sb.contains("+ ")) CheckStatus.OK else CheckStatus.WARN
    return CheckResult("android.car Detail", status, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkCarPropertyCamera(): CheckResult {
    val sb = StringBuilder()
    try {
        val carClass = Class.forName("android.car.Car")
        val car = carClass.getMethod("createCar", android.content.Context::class.java).invoke(null, context)
        val getManager = carClass.getMethod("getCarManager", String::class.java)
        val propMgr = getManager.invoke(car, "property") ?: run {
            return CheckResult("CarPropertyManager", CheckStatus.WARN, "CarPropertyManager недоступен")
        }

        val propMgrClass = propMgr.javaClass
        sb.appendLine("CarPropertyManager: доступен")

        val propsToCheck = mapOf(
            "GEAR_SELECTION" to 289408000,
            "CURRENT_GEAR" to 289408001,
            "PARKING_BRAKE_ON" to 287310858,
            "IGNITION_STATE" to 289408009,
            "NIGHT_MODE" to 287310855,
            "PERF_VEHICLE_SPEED" to 291504647,
        )

        val getIntProp = propMgrClass.methods.firstOrNull { m ->
            m.name == "getIntProperty" && m.parameterCount == 2
        }

        for ((name, propId) in propsToCheck) {
            try {
                val value = getIntProp?.invoke(propMgr, propId, 0)
                sb.appendLine("$name ($propId): $value")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                sb.appendLine("$name ($propId): ${cause?.javaClass?.simpleName}: ${cause?.message?.take(60)}")
            } catch (e: Exception) {
                sb.appendLine("$name ($propId): ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            }
        }

        try {
            val getPropList = propMgrClass.getMethod("getPropertyList")
            @Suppress("UNCHECKED_CAST")
            val list = getPropList.invoke(propMgr) as? List<*>
            val cameraProps = list?.filter { item ->
                item?.toString()?.contains("camera", true) == true ||
                item?.toString()?.contains("EVS", true) == true
            }
            if (!cameraProps.isNullOrEmpty()) sb.appendLine("\nCamera-related properties: $cameraProps")
            else sb.appendLine("\nCamera-related properties в списке: нет")
        } catch (_: Exception) {}

        try { carClass.getMethod("disconnect").invoke(car) } catch (_: Exception) {}

    } catch (e: ClassNotFoundException) {
        return CheckResult("CarPropertyManager", CheckStatus.INFO, "android.car не найден")
    } catch (e: Exception) {
        return CheckResult("CarPropertyManager", CheckStatus.WARN, "Ошибка: ${e.message}")
    }

    return CheckResult("CarPropertyManager", CheckStatus.OK, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkCarVendorExtension(): CheckResult {
    val sb = StringBuilder()
    try {
        val carClass = Class.forName("android.car.Car")
        val car = carClass.getMethod("createCar", android.content.Context::class.java).invoke(null, context)
        val getManager = carClass.getMethod("getCarManager", String::class.java)

        val vendorMgr = getManager.invoke(car, "vendor_extension") ?: run {
            return CheckResult("CarVendorExtension", CheckStatus.WARN, "CarVendorExtensionManager недоступен")
        }
        sb.appendLine("CarVendorExtensionManager: доступен (${vendorMgr.javaClass.name})")

        val mgrClass = vendorMgr.javaClass
        val getPropMethod = mgrClass.methods.firstOrNull { it.name == "getProperties" && it.parameterCount == 0 }
        if (getPropMethod != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val props = getPropMethod.invoke(vendorMgr) as? Collection<*>
                if (!props.isNullOrEmpty()) {
                    sb.appendLine("getProperties() — ${props.size} vendor properties:")
                    props.take(30).forEach { p -> sb.appendLine("  $p") }
                    if (props.size > 30) sb.appendLine("  ... ещё ${props.size - 30}")
                } else {
                    sb.appendLine("getProperties(): пустой список")
                }
            } catch (e: Exception) {
                sb.appendLine("getProperties(): ${e.message?.take(100)}")
            }
        } else {
            sb.appendLine("getProperties() не найден")
        }

        val methods = mgrClass.methods.filter { !it.declaringClass.name.contains("Object") }
            .map { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" }
        sb.appendLine("\nМетоды:")
        methods.take(15).forEach { sb.appendLine("  $it") }

        try { carClass.getMethod("disconnect").invoke(car) } catch (_: Exception) {}

    } catch (_: ClassNotFoundException) {
        return CheckResult("CarVendorExtension", CheckStatus.INFO, "android.car не найден")
    } catch (e: Exception) {
        return CheckResult("CarVendorExtension", CheckStatus.WARN, "Ошибка: ${e.message}")
    }
    return CheckResult("CarVendorExtension", CheckStatus.OK, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkVendorSockets(): CheckResult {
    val sb = StringBuilder()

    val sockets = File("/dev/socket").listFiles()
        ?.filter { f -> listOf("cam", "evs", "avm", "dvr", "video", "vin").any { f.name.contains(it, true) } }
        ?.map { it.name } ?: emptyList()

    if (sockets.isNotEmpty()) sb.appendLine("/dev/socket/: ${sockets.joinToString()}")
    else sb.appendLine("/dev/socket/: нет camera/evs/avm сокетов")

    val vendorBins = File("/vendor/bin").listFiles()
        ?.filter { f -> listOf("cam", "evs", "avm", "dvr", "video", "vin", "cameraserver").any { f.name.contains(it, true) } }
        ?.map { it.name } ?: emptyList()

    if (vendorBins.isNotEmpty()) sb.appendLine("/vendor/bin/: ${vendorBins.joinToString()}")
    else sb.appendLine("/vendor/bin/: нет camera/evs/avm бинарей")

    val vendorEtc = File("/vendor/etc").listFiles()
        ?.filter { f -> listOf("cam", "evs", "avm", "dvr").any { f.name.contains(it, true) } }
        ?.map { it.name } ?: emptyList()
    if (vendorEtc.isNotEmpty()) sb.appendLine("/vendor/etc/: ${vendorEtc.joinToString()}")

    val allVendorHw = listOf("/vendor/lib/hw", "/vendor/lib64/hw").flatMap { dir ->
        File(dir).listFiles()
            ?.filter { f -> listOf("cam", "evs", "avm", "video").any { f.name.contains(it, true) } }
            ?.map { "$dir/${it.name}" } ?: emptyList()
    }
    if (allVendorHw.isNotEmpty()) sb.appendLine("/vendor/lib*/hw/: ${allVendorHw.joinToString()}")

    val hidlManifest = try {
        File("/vendor/manifest.xml").readText()
            .lines()
            .filter { it.contains("camera", true) || it.contains("evs", true) }
            .take(10)
    } catch (_: Exception) { emptyList() }

    if (hidlManifest.isNotEmpty()) {
        sb.appendLine("\nmanifest.xml entries:")
        hidlManifest.forEach { sb.appendLine("  ${it.trim()}") }
    }

    val hasSomething = sockets.isNotEmpty() || vendorBins.isNotEmpty() || hidlManifest.isNotEmpty()
    return CheckResult("Vendor Сокеты / Бинари", if (hasSomething) CheckStatus.OK else CheckStatus.INFO, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkVendorInitFiles(): CheckResult {
    val sb = StringBuilder()
    val initDirs = listOf("/vendor/etc/init", "/system/etc/init", "/odm/etc/init")
    var foundAny = false

    for (dir in initDirs) {
        val rcFiles = File(dir).listFiles { f ->
            f.name.endsWith(".rc") &&
            listOf("cam", "evs", "avm", "dvr", "video", "vin").any { f.name.contains(it, true) }
        } ?: continue

        if (rcFiles.isNotEmpty()) {
            foundAny = true
            sb.appendLine("$dir/:")
            for (f in rcFiles) {
                sb.appendLine("  ${f.name}")
                try {
                    val interesting = f.readLines().take(60).filter { line ->
                        listOf("service ", "class ", "user ", "group ", "trigger", "on prop:", "oneshot")
                            .any { line.trimStart().startsWith(it) }
                    }
                    interesting.forEach { sb.appendLine("    $it") }
                } catch (e: Exception) {
                    sb.appendLine("    (нет доступа: ${e.message})")
                }
            }
        }
    }

    val shellR = shell("find", "/vendor/etc/init", "-name", "*cam*", "-o", "-name", "*evs*", "-o", "-name", "*video*")
    if (shellR.stdout.isNotEmpty()) {
        sb.appendLine("\nfind /vendor/etc/init:\n${shellR.stdout}")
        foundAny = true
    }

    if (!foundAny) return CheckResult("Vendor Init Files", CheckStatus.WARN,
        "camera/evs/avm RC файлы не найдены в /vendor/etc/init/ и /system/etc/init/")
    return CheckResult("Vendor Init Files", CheckStatus.OK, sb.toString().trim())
}

internal fun DiagnosticsEngine.checkVendorCameraProps(): CheckResult {
    val sb = StringBuilder()
    val clazz = try { Class.forName("android.os.SystemProperties") } catch (_: Exception) { null }
    val getMethod = clazz?.getMethod("get", String::class.java, String::class.java)

    if (getMethod != null) {
        val knownProps = listOf(
            "ro.hardware.camera", "ro.camera.hal.version",
            "vendor.camera.aux.packagelist", "persist.camera.tnr.video",
            "vendor.camera.hal.debug", "persist.vendor.camera.privapp.list",
            "ro.vendor.camera.extensions.package",
        )
        for (prop in knownProps) {
            val v = try { getMethod.invoke(null, prop, "") as String } catch (_: Exception) { "" }
            if (v.isNotEmpty()) sb.appendLine("$prop=$v")
        }
    }

    val r = shell("getprop", timeoutMs = 8000)
    if (r.stdout.isNotEmpty()) {
        val cameraProps = r.stdout.lines().filter { line ->
            listOf("camera", "cam", "evs", "vin", "csi", "isp", "sensor")
                .any { line.lowercase().contains(it) } &&
            !line.contains("bootanim") && !line.contains("mediacodec")
        }
        if (cameraProps.isNotEmpty()) {
            sb.appendLine("\ngetprop camera-related:")
            cameraProps.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("getprop: camera-related свойства не найдены")
        }
    } else {
        sb.appendLine("getprop: нет вывода (${r.stderr.take(60)})")
    }

    val hasContent = sb.isNotEmpty()
    return CheckResult("Vendor Camera Properties",
        if (hasContent) CheckStatus.INFO else CheckStatus.WARN,
        sb.toString().trim().ifEmpty { "Свойства не найдены" })
}
