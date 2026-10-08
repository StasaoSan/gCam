package com.stasao.gcam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class GCamRelease(
    val tag: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String
)

internal object GCamUpdater {
    private const val RELEASE_REPOSITORY = "StasaoSan/gCam"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun supportsUpdates(context: Context): Boolean = context.packageName == "com.stasao.gcam"

    suspend fun latestRelease(context: Context): GCamRelease? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "gCam-updater")
        try {
            when (connection.responseCode) {
                200 -> Unit
                404 -> throw IllegalStateException("Релиз не найден. Для обновлений репозиторий и Release должны быть публичными.")
                403 -> throw IllegalStateException("GitHub временно ограничил запросы. Попробуйте позже.")
                else -> throw IllegalStateException("GitHub вернул HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            require(Regex("v[0-9]+(?:\\.[0-9]+){1,3}").matches(tag)) { "Некорректный тег релиза" }
            if (!isNewer(tag.removePrefix("v"), installedVersion(context))) return@withContext null

            val expectedName = "gCam_[$tag].apk"
            val assets = json.getJSONArray("assets")
            val asset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == expectedName }
                ?: throw IllegalStateException("В релизе $tag нет файла $expectedName")
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_BYTES) { "Некорректный размер APK" }
            val digest = asset.optString("digest")
            require(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) { "У APK нет контрольной суммы SHA-256" }
            val downloadUrl = asset.getString("browser_download_url")
            val uri = Uri.parse(downloadUrl)
            val expectedPath = "/$RELEASE_REPOSITORY/releases/download/$tag/$expectedName"
            require(uri.scheme == "https" && uri.host == "github.com" &&
                Uri.decode(uri.encodedPath) == expectedPath && uri.query == null
            ) { "Некорректный адрес APK" }
            GCamRelease(tag, json.optString("body"), downloadUrl, size, digest.removePrefix("sha256:").lowercase())
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, release: GCamRelease, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = File(folder, "gCam-${release.tag}.apk")
            val partial = File(folder, "gCam-${release.tag}.apk.part")
            if (apk.isFile && apk.length() == release.sizeBytes && sha256(apk) == release.sha256) {
                if (runCatching { verifyPackage(context, apk, release) }.isSuccess) {
                    onProgress(1f)
                    return@withContext apk
                }
            }
            apk.delete()

            val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "gCam-updater")
            try {
                require(connection.responseCode == 200) { "Не удалось скачать APK: HTTP ${connection.responseCode}" }
                var copied = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= release.sizeBytes) { "Размер APK превышает заявленный" }
                            output.write(buffer, 0, count)
                            onProgress(copied.toFloat() / release.sizeBytes)
                        }
                    }
                }
                require(copied == release.sizeBytes) { "APK скачан не полностью" }
                require(sha256(partial) == release.sha256) { "Контрольная сумма APK не совпадает" }
                require(partial.renameTo(apk)) { "Не удалось сохранить APK" }
                try {
                    verifyPackage(context, apk, release)
                } catch (error: Exception) {
                    apk.delete()
                    throw error
                }
                apk
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")))
    }

    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun verifyPackage(context: Context, file: File, release: GCamRelease) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException("Скачанный файл не является APK")
        require(candidate.packageName == context.packageName) { "APK предназначен для другого приложения" }
        require(candidate.versionName == release.tag.removePrefix("v")) { "Версия APK не совпадает с релизом" }
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(candidate.longVersionCode > installed.longVersionCode) { "Номер сборки не новее установленного" }
        val currentSigners = installed.signingInfo?.apkContentsSigners.orEmpty()
        val newSigners = candidate.signingInfo?.apkContentsSigners.orEmpty()
        require(currentSigners.isNotEmpty() && newSigners.isNotEmpty() &&
            currentSigners.size == newSigners.size &&
            currentSigners.all { current -> newSigners.any { it.toByteArray().contentEquals(current.toByteArray()) } }
        ) { "Подпись APK не совпадает с установленным gCam" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val first = candidate.split('.').mapNotNull(String::toIntOrNull)
        val second = current.split('.').mapNotNull(String::toIntOrNull)
        if (first.isEmpty() || second.isEmpty()) return false
        repeat(maxOf(first.size, second.size)) { index ->
            val a = first.getOrElse(index) { 0 }
            val b = second.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
