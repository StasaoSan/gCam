package com.stasao.gcam

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Authorization for the exported camera Binder; no shared secret is embedded in ANHUD. */
internal object CaptureClientTrust {
    private const val ANHUD_PACKAGE = "com.g992.anhud"
    private const val BLIND_PACKAGE = "com.stasao.gblind"
    private const val ZONES_PACKAGE = "com.stasao.gzones"
    private const val TRUST_FILE = "anhud_camera_signer.sha256"
    private const val BLIND_TRUST_FILE = "gblind_camera_signer.sha256"
    private const val ZONES_TRUST_FILE = "gzones_camera_signer.sha256"

    data class Status(
        val installed: Boolean,
        val sameSigner: Boolean,
        val approved: Boolean,
        val signerChanged: Boolean,
    )

    fun status(context: Context): Status = statusFor(context, ANHUD_PACKAGE, TRUST_FILE)
    fun blindStatus(context: Context): Status = statusFor(context, BLIND_PACKAGE, BLIND_TRUST_FILE)
    fun zonesStatus(context: Context): Status = statusFor(context, ZONES_PACKAGE, ZONES_TRUST_FILE)

    private fun statusFor(context: Context, appPackage: String, fileName: String): Status {
        val signer = currentSigner(context, appPackage) ?: return Status(false, false, false, false)
        val sameSigner = context.packageManager.checkSignatures(context.packageName, appPackage) ==
            PackageManager.SIGNATURE_MATCH
        val saved = savedSigner(context, fileName)
        return Status(true, sameSigner, saved == signer, saved != null && saved != signer)
    }

    fun approve(context: Context): Boolean = approveFor(context, ANHUD_PACKAGE, TRUST_FILE)
    fun approveBlind(context: Context): Boolean = approveFor(context, BLIND_PACKAGE, BLIND_TRUST_FILE)
    fun approveZones(context: Context): Boolean = approveFor(context, ZONES_PACKAGE, ZONES_TRUST_FILE)

    private fun approveFor(context: Context, appPackage: String, fileName: String): Boolean {
        val signer = currentSigner(context, appPackage) ?: return false
        val file = trustFile(context, fileName)
        val output = file.startWrite()
        try {
            output.write(signer.toByteArray(Charsets.US_ASCII))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return true
    }

    fun revoke(context: Context) = trustFile(context, TRUST_FILE).delete()
    fun revokeBlind(context: Context) = trustFile(context, BLIND_TRUST_FILE).delete()
    fun revokeZones(context: Context) = trustFile(context, ZONES_TRUST_FILE).delete()

    fun isBlindCaller(context: Context, uid: Int): Boolean =
        context.packageManager.getPackagesForUid(uid)?.contains(BLIND_PACKAGE) == true

    fun isZonesCaller(context: Context, uid: Int): Boolean =
        context.packageManager.getPackagesForUid(uid)?.contains(ZONES_PACKAGE) == true

    fun enforceCaller(context: Context, callingUid: Int, transactionCode: Int) {
        if (callingUid == Process.myUid()) return
        val externalHudCall = when (transactionCode) {
            IQCarCamCapture.Stub.TRANSACTION_startHud,
            IQCarCamCapture.Stub.TRANSACTION_configureHud,
            IQCarCamCapture.Stub.TRANSACTION_stopHud,
            IQCarCamCapture.Stub.TRANSACTION_hudStatus,
            IQCarCamCapture.Stub.TRANSACTION_hudStats,
            IQCarCamCapture.Stub.TRANSACTION_startHudV2,
            IQCarCamCapture.Stub.TRANSACTION_configureHudV2,
            IQCarCamCapture.Stub.TRANSACTION_setHudClientActive,
            IQCarCamCapture.Stub.TRANSACTION_warmInput -> true
            else -> false
        }
        val blindReport = transactionCode == IQCarCamCapture.Stub.TRANSACTION_reportBlindHealth
        if (!externalHudCall && !blindReport) throw SecurityException("Записью может управлять только gCam")
        val manager = context.packageManager
        // Check the actual Binder UID first; an Intent extra or a claimed package name is forgeable.
        val blind = isBlindCaller(context, callingUid)
        val zones = isZonesCaller(context, callingUid)
        val appPackage = when {
            zones -> ZONES_PACKAGE
            blind -> BLIND_PACKAGE
            else -> ANHUD_PACKAGE
        }
        val fileName = when {
            zones -> ZONES_TRUST_FILE
            blind -> BLIND_TRUST_FILE
            else -> TRUST_FILE
        }
        if (manager.getPackagesForUid(callingUid)?.contains(appPackage) != true ||
            (blindReport && !blind)) {
            throw SecurityException("Доступ к камерам gCam разрешён только ANHUD, gBlind или gZones")
        }
        if (blind && !blindReport && transactionCode !in setOf(
                IQCarCamCapture.Stub.TRANSACTION_startHudV2,
                IQCarCamCapture.Stub.TRANSACTION_configureHudV2,
                IQCarCamCapture.Stub.TRANSACTION_stopHud,
                IQCarCamCapture.Stub.TRANSACTION_hudStatus,
                IQCarCamCapture.Stub.TRANSACTION_hudStats)) {
            throw SecurityException("gBlind не может управлять проекцией")
        }
        if (zones && transactionCode !in setOf(
                IQCarCamCapture.Stub.TRANSACTION_startHudV2,
                IQCarCamCapture.Stub.TRANSACTION_stopHud,
                IQCarCamCapture.Stub.TRANSACTION_hudStatus,
                IQCarCamCapture.Stub.TRANSACTION_hudStats)) {
            throw SecurityException("gZones может только читать свои потоки")
        }
        if (manager.checkSignatures(callingUid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) {
            return // Existing debug builds signed by the same Android debug key.
        }
        val signer = currentSigner(context, appPackage)
        if (signer == null || signer != savedSigner(context, fileName)) {
            throw SecurityException("Откройте gCam и подтвердите доступ $appPackage к камерам")
        }
    }

    private fun trustFile(context: Context, name: String) = AtomicFile(File(context.filesDir, name))

    private fun savedSigner(context: Context, fileName: String): String? = runCatching {
        trustFile(context, fileName).openRead().bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
            .takeIf { it.matches(Regex("[0-9a-f]{64}(,[0-9a-f]{64})*")) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun currentSigner(context: Context, appPackage: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(appPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.isEmpty()) return@runCatching null
        signers.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.sorted().joinToString(",")
    }.getOrNull()
}
