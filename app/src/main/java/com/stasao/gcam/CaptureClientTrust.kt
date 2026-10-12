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
    private const val TRUST_FILE = "anhud_camera_signer.sha256"

    data class Status(
        val installed: Boolean,
        val sameSigner: Boolean,
        val approved: Boolean,
        val signerChanged: Boolean,
    )

    fun status(context: Context): Status {
        val signer = currentSigner(context) ?: return Status(false, false, false, false)
        val sameSigner = context.packageManager.checkSignatures(context.packageName, ANHUD_PACKAGE) ==
            PackageManager.SIGNATURE_MATCH
        val saved = savedSigner(context)
        return Status(true, sameSigner, saved == signer, saved != null && saved != signer)
    }

    fun approve(context: Context): Boolean {
        val signer = currentSigner(context) ?: return false
        val file = trustFile(context)
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

    fun revoke(context: Context) = trustFile(context).delete()

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
            IQCarCamCapture.Stub.TRANSACTION_warmInput -> true
            else -> false
        }
        if (!externalHudCall) throw SecurityException("Записью может управлять только gCam")
        val manager = context.packageManager
        // Check the actual Binder UID first; an Intent extra or a claimed package name is forgeable.
        if (manager.getPackagesForUid(callingUid)?.contains(ANHUD_PACKAGE) != true) {
            throw SecurityException("Доступ к камерам gCam разрешён только ANHUD")
        }
        if (manager.checkSignatures(callingUid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) {
            return // Existing debug builds signed by the same Android debug key.
        }
        val signer = currentSigner(context)
        if (signer == null || signer != savedSigner(context)) {
            throw SecurityException("Откройте gCam и подтвердите доступ ANHUD к камерам")
        }
    }

    private fun trustFile(context: Context) = AtomicFile(File(context.filesDir, TRUST_FILE))

    private fun savedSigner(context: Context): String? = runCatching {
        trustFile(context).openRead().bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
            .takeIf { it.matches(Regex("[0-9a-f]{64}(,[0-9a-f]{64})*")) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun currentSigner(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(ANHUD_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.isEmpty()) return@runCatching null
        signers.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.sorted().joinToString(",")
    }.getOrNull()
}
