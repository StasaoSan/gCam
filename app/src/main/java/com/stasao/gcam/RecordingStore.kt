package com.stasao.gcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingTarget(val uri: Uri, val descriptor: ParcelFileDescriptor, val storage: RecordingStorage)
data class StorageStats(val totalBytes: Long, val freeBytes: Long, val recordingBytes: Long)
data class ArchiveMaintenance(val deletedFiles: Int, val usedBytes: Long, val freeBytes: Long,
                              val ready: Boolean)
data class RemovableDrive(val volume: StorageVolume?, val id: String, val name: String,
                          val availableBytes: Long = -1L)

object RecordingStore {
    private const val ROOT = "gCam"
    private const val PROTECTED = "protected"
    const val RESERVED_FREE_BYTES = 2L * 1024 * 1024 * 1024
    private const val GB = 1024L * 1024L * 1024L
    // The synthetic "external" volume may include USB files. Keep the internal
    // archive scoped to primary storage so its quota never counts or deletes USB clips.
    private val collection: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val rootPath = "${Environment.DIRECTORY_MOVIES}/$ROOT/"

    @Synchronized fun createSegment(context: Context, inputId: Int, name: String,
                                    protected: Boolean = false,
                                    storage: RecordingStorage = RecordingStorage.INTERNAL): RecordingTarget {
        if (storage == RecordingStorage.EXTERNAL) {
            validateExternal(context)?.let { error(it) }
            return createMediaSegment(context, externalCollection(context), inputId, name, protected, storage)
        }
        return createMediaSegment(context, collection, inputId, name, protected, storage)
    }

    private fun createMediaSegment(context: Context, destination: Uri, inputId: Int, name: String,
                                   protected: Boolean, storage: RecordingStorage): RecordingTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (protected) "$rootPath$PROTECTED/" else cameraPath(inputId))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(destination, values)) {
            "Не удалось создать файл в Movies/gCam на выбранном накопителе"
        }
        return try {
            RecordingTarget(uri, requireNotNull(context.contentResolver.openFileDescriptor(uri, "w")), storage)
        } catch (t: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw t
        }
    }

    fun finishSegment(context: Context, target: RecordingTarget) {
        val size = runCatching { target.descriptor.statSize }.getOrDefault(-1L)
        runCatching { target.descriptor.fileDescriptor.sync() }.onFailure {
            RecorderDiagnostics.record(context,
                "segment_sync_error uri=${target.uri} type=${it.javaClass.simpleName} message=${it.message}")
        }
        target.descriptor.close()
        val updated = context.contentResolver.update(target.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }, null, null)
        check(updated > 0) { "Не удалось опубликовать завершённый MP4" }
        RecorderDiagnostics.record(context, "segment_published uri=${target.uri} bytes=$size")
    }

    fun abortSegment(context: Context, target: RecordingTarget) {
        runCatching { target.descriptor.close() }
        context.contentResolver.delete(target.uri, null, null)
    }

    fun list(context: Context): List<RecordingFile> = listInternal(context) + listExternal(context)

    private fun listInternal(context: Context): List<RecordingFile> = listMedia(context, collection, RecordingStorage.INTERNAL)

    private fun listMedia(context: Context, destination: Uri, storage: RecordingStorage): List<RecordingFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        return buildList {
            context.contentResolver.query(
                destination, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf("$rootPath%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    // Special storage access also sees files belonging to other apps.
                    // Only recognized gCam clips inside our archive may enter retention.
                    if (!name.matches(RECORDING_NAME)) continue
                    val path = cursor.getString(pathIndex).orEmpty()
                    add(RecordingFile(
                        ContentUris.withAppendedId(destination, cursor.getLong(idIndex)).toString(),
                        name,
                        Regex("cam(\\d)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1,
                        cursor.getLong(sizeIndex),
                        cursor.getLong(modifiedIndex) * 1000,
                        path.contains("/$PROTECTED/"),
                        cursor.getLong(durationIndex).coerceAtLeast(0),
                        storage
                    ))
                }
            }
        }
    }

    fun usedBytes(context: Context, storage: RecordingStorage = RecorderSettings.load(context).storage): Long =
        listForStorage(context, storage).sumOf(RecordingFile::sizeBytes)

    private fun listForStorage(context: Context, storage: RecordingStorage): List<RecordingFile> =
        if (storage == RecordingStorage.EXTERNAL) listExternal(context) else listInternal(context)

    /** For the player only; metadata fallback may open MP4 files and must run off the UI thread. */
    fun listWithDurations(context: Context): List<RecordingFile> = list(context).map { file ->
        if (file.durationMs > 0) file else {
            val duration = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, Uri.parse(file.uri))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
            }.getOrDefault(0L)
            file.copy(durationMs = duration.coerceAtLeast(0))
        }
    }

    fun storageStats(context: Context, storage: RecordingStorage = RecorderSettings.load(context).storage): StorageStats {
        val drive = if (storage == RecordingStorage.EXTERNAL) selectedDrive(context) else null
        val path = if (storage == RecordingStorage.INTERNAL) Environment.getExternalStorageDirectory().absolutePath
                   else drive?.volume?.directory?.absolutePath
                       ?: drive?.id?.let { "/storage/$it" }?.takeIf { File(it).exists() }
        val stat = path?.let { runCatching { StatFs(it) }.getOrNull() }
        return StorageStats(stat?.totalBytes ?: -1L,
            stat?.availableBytes ?: drive?.availableBytes ?: -1L, usedBytes(context, storage))
    }

    fun maxArchiveGb(context: Context, storage: RecordingStorage = RecorderSettings.load(context).storage): Int? {
        val stats = storageStats(context, storage)
        if (stats.freeBytes < 0) return null
        val available = (stats.freeBytes / GB) + (stats.recordingBytes / GB) - (RESERVED_FREE_BYTES / GB)
        return available.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Synchronized fun enforceLimit(context: Context, config: RecorderConfig): ArchiveMaintenance {
        val files = listForStorage(context, config.storage)
        val stats = storageStats(context, config.storage)
        return ArchiveRetention.enforce(files, config, stats.recordingBytes, stats.freeBytes) { recording ->
            val removed = try {
                delete(context, recording)
            } catch (denied: SecurityException) {
                val owner = runCatching {
                    context.contentResolver.query(Uri.parse(recording.uri),
                        arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
                RecorderDiagnostics.record(context,
                    "archive_delete_denied uri=${recording.uri} name=${recording.name} owner=$owner " +
                        "storage=${config.storage} initialUsedBytes=${stats.recordingBytes} " +
                        "initialFreeBytes=${stats.freeBytes} limitGb=${config.storageLimitGb} " +
                        "allFilesAccess=${ArchiveAccess.isGranted()} type=${denied.javaClass.simpleName}")
                throw ArchiveAccessRequiredException(denied)
            }
            RecorderDiagnostics.record(context,
                "archive_delete uri=${recording.uri} bytes=${recording.sizeBytes} deleted=$removed")
            removed
        }
    }

    @Synchronized fun setProtected(context: Context, recording: RecordingFile, protect: Boolean): Boolean {
        if (recording.protected == protect) return true
        if (recording.storage == RecordingStorage.EXTERNAL && Uri.parse(recording.uri).authority != "media") {
            val prefs = context.getSharedPreferences("protected_usb_recordings", Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (protect) putBoolean(recording.uri, true) else remove(recording.uri)
            }.apply()
            return true
        }
        val path = if (protect) "$rootPath$PROTECTED/" else cameraPath(recording.inputId)
        return context.contentResolver.update(Uri.parse(recording.uri), ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        }, null, null) > 0
    }

    @Synchronized fun delete(context: Context, recording: RecordingFile): Boolean {
        val deleted = context.contentResolver.delete(Uri.parse(recording.uri), null, null) > 0
        if (deleted && recording.storage == RecordingStorage.EXTERNAL) {
            context.getSharedPreferences("protected_usb_recordings", Context.MODE_PRIVATE).edit()
                .remove(recording.uri).apply()
        }
        return deleted
    }

    fun removeInterrupted(context: Context, storage: RecordingStorage = RecordingStorage.INTERNAL) {
        val destination = if (storage == RecordingStorage.EXTERNAL) externalCollection(context) else collection
        context.contentResolver.query(
            destination, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=1 " +
                "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf("$rootPath%", context.packageName), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.getString(1).orEmpty().matches(RECORDING_NAME)) continue
                val uri = ContentUris.withAppendedId(destination, cursor.getLong(0))
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (denied: SecurityException) {
                    // Orphaned pending files from another installation are not active segments.
                    // Their ownership must not block recording new files.
                    RecorderDiagnostics.record(context,
                        "archive_interrupted_delete_denied uri=$uri type=${denied.javaClass.simpleName} message=${denied.message}")
                }
            }
        }
    }

    /** Moves recordings made by v4.0 from private app storage into visible Movies. */
    @Synchronized fun migrateLegacy(context: Context) {
        val legacyRoot = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), ROOT)
        if (!legacyRoot.exists()) return
        legacyRoot.walkTopDown().filter { it.isFile && it.extension == "mp4" }.forEach { source ->
            val inputId = Regex("cam(\\d)").find(source.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach
            var target: RecordingTarget? = null
            runCatching {
                target = createSegment(context, inputId, source.name, source.parentFile?.name == PROTECTED,
                    RecordingStorage.INTERNAL)
                ParcelFileDescriptor.AutoCloseOutputStream(target!!.descriptor).use { output ->
                    source.inputStream().use { it.copyTo(output) }
                }
                finishSegment(context, target!!)
                source.delete()
            }.onFailure { target?.let { abortSegment(context, it) } }
        }
    }

    private fun cameraPath(inputId: Int) = "$rootPath${"camera_$inputId"}/"

    /** Only a removable volume is accepted; an emulated internal directory is not a USB destination. */
    fun validateExternal(context: Context): String? {
        val drive = selectedDrive(context) ?: return "Выберите подключённый USB-накопитель"
        if (mediaVolumeName(context, drive) == null)
            return "USB-накопитель ${drive.name} не доступен через медиахранилище Android"
        return null
    }

    /** Checks the actual MediaStore write path, not just the mount flag. */
    fun probeExternalWritable(context: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val target = createMediaSegment(context, externalCollection(context), 0,
            "cam0_${stamp}_s0.mp4", false, RecordingStorage.EXTERNAL)
        try {
            FileOutputStream(target.descriptor.fileDescriptor).use { it.write(0) }
        } finally {
            abortSegment(context, target)
        }
    }

    fun treeVolumeId(tree: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(tree).substringBefore(':').takeIf(String::isNotBlank)
    }.getOrNull()

    fun removableDrives(context: Context): List<RemovableDrive> {
        val volumes = (context.getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && !it.uuid.isNullOrBlank() }
            .map { RemovableDrive(it, requireNotNull(it.uuid), it.getDescription(context)) }
        // Some vendor builds expose a USB root to the system file picker but omit it from StorageManager.
        val authority = "com.android.externalstorage.documents"
        val roots = runCatching {
            buildList {
                context.contentResolver.query(DocumentsContract.buildRootsUri(authority), arrayOf(
                    DocumentsContract.Root.COLUMN_ROOT_ID,
                    DocumentsContract.Root.COLUMN_TITLE,
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
                ), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        if (id.equals("primary", true) || id.equals("home", true)) continue
                        add(RemovableDrive(null, id, cursor.getString(1) ?: "USB $id",
                            if (cursor.isNull(2)) -1L else cursor.getLong(2)))
                    }
                }
            }
        }.getOrDefault(emptyList())
        return (volumes + roots).groupBy { it.id.uppercase(java.util.Locale.ROOT) }
            .values.map { group ->
                val volume = group.firstOrNull { it.volume != null } ?: group.first()
                volume.copy(availableBytes = group.firstOrNull { it.availableBytes >= 0 }?.availableBytes ?: -1L)
            }
    }

    fun selectedDrive(context: Context): RemovableDrive? {
        val drives = removableDrives(context)
        val id = RecorderSettings.externalDriveId(context)
        return drives.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: drives.singleOrNull().takeIf { id == null }
    }

    private fun mediaVolumeName(context: Context, drive: RemovableDrive): String? {
        val candidate = drive.volume?.mediaStoreVolumeName ?: drive.id
        return MediaStore.getExternalVolumeNames(context).firstOrNull {
            it.equals(candidate, ignoreCase = true) || it.equals(drive.id, ignoreCase = true)
        }
    }

    private fun externalCollection(context: Context): Uri {
        val drive = requireNotNull(selectedDrive(context)) { "Выберите подключённый USB-накопитель" }
        val volumeName = requireNotNull(mediaVolumeName(context, drive)) {
            "USB-накопитель ${drive.name} не доступен через медиахранилище Android"
        }
        return MediaStore.Video.Media.getContentUri(volumeName)
    }

    fun isRemovableTree(context: Context, tree: Uri): Boolean =
        removableDrives(context).any { it.id.equals(treeVolumeId(tree), ignoreCase = true) }

    fun hasExternalTreePermission(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }

    private fun treeRoot(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree))

    private fun childDocuments(context: Context, tree: Uri, parent: Uri): List<Pair<Uri, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        return buildList {
            context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    add(uri to cursor.getString(1))
                }
            }
        }
    }

    private fun findOrCreateDirectory(context: Context, tree: Uri, parent: Uri, name: String): Uri =
        childDocuments(context, tree, parent).firstOrNull { it.second == name }?.first
            ?: requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name)) { "Не удалось создать папку $name на USB" }

    private fun listExternal(context: Context): List<RecordingFile> {
        val mediaFiles = runCatching { listMedia(context, externalCollection(context), RecordingStorage.EXTERNAL) }
            .getOrDefault(emptyList())
        val legacyFiles = listExternalTree(context)
        return (mediaFiles + legacyFiles).distinctBy(RecordingFile::name)
    }

    private fun listExternalTree(context: Context): List<RecordingFile> {
        val tree = RecorderSettings.externalTree(context) ?: return emptyList()
        if (!treeVolumeId(tree).equals(selectedDrive(context)?.id, ignoreCase = true) ||
            !hasExternalTreePermission(context, tree)) return emptyList()
        return runCatching {
            val root = treeRoot(tree)
            val gcam = childDocuments(context, tree, root).firstOrNull { it.second == ROOT }?.first
                ?: return@runCatching emptyList()
            val protectedPrefs = context.getSharedPreferences("protected_usb_recordings", Context.MODE_PRIVATE)
            buildList {
                for ((folder, folderName) in childDocuments(context, tree, gcam)) {
                    if (folderName != PROTECTED && !folderName.startsWith("camera_")) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getDocumentId(folder))
                    context.contentResolver.query(children, arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            if (cursor.getString(2) != "video/mp4") continue
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString()
                            val name = cursor.getString(1)
                            if (!name.matches(RECORDING_NAME)) continue
                            add(RecordingFile(uri, name,
                                Regex("cam(\\d)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1,
                                cursor.getLong(3), cursor.getLong(4),
                                folderName == PROTECTED || protectedPrefs.getBoolean(uri, false),
                                storage = RecordingStorage.EXTERNAL))
                        }
                    }
                }
            }.sortedByDescending(RecordingFile::modifiedAt)
        }.getOrElse { emptyList() }
    }

    private val RECORDING_NAME = Regex("cam[0-3]_\\d{8}_\\d{6}_\\d{3}(?:_s\\d+)?\\.mp4")
}
