package com.stasao.gcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

data class RecordingTarget(val uri: Uri, val descriptor: ParcelFileDescriptor)
data class StorageStats(val totalBytes: Long, val freeBytes: Long, val recordingBytes: Long)

object RecordingStore {
    private const val ROOT = "gCam"
    private const val PROTECTED = "protected"
    const val RESERVED_FREE_BYTES = 5L * 1024 * 1024 * 1024
    private const val GB = 1024L * 1024L * 1024L
    private val collection: Uri get() = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    private val rootPath = "${Environment.DIRECTORY_MOVIES}/$ROOT/"

    fun createSegment(context: Context, inputId: Int, name: String, protected: Boolean = false): RecordingTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (protected) "$rootPath$PROTECTED/" else cameraPath(inputId))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) {
            "Не удалось создать файл в Movies/gCam"
        }
        return try {
            RecordingTarget(uri, requireNotNull(context.contentResolver.openFileDescriptor(uri, "w")))
        } catch (t: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw t
        }
    }

    fun finishSegment(context: Context, target: RecordingTarget) {
        runCatching { target.descriptor.close() }
        context.contentResolver.update(target.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }, null, null)
    }

    fun abortSegment(context: Context, target: RecordingTarget) {
        runCatching { target.descriptor.close() }
        context.contentResolver.delete(target.uri, null, null)
    }

    fun list(context: Context): List<RecordingFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        return buildList {
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf("$rootPath%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val path = cursor.getString(pathIndex).orEmpty()
                    add(RecordingFile(
                        ContentUris.withAppendedId(collection, cursor.getLong(idIndex)).toString(),
                        name,
                        Regex("cam(\\d)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1,
                        cursor.getLong(sizeIndex),
                        cursor.getLong(modifiedIndex) * 1000,
                        path.contains("/$PROTECTED/")
                    ))
                }
            }
        }
    }

    fun usedBytes(context: Context): Long = list(context).sumOf(RecordingFile::sizeBytes)

    fun storageStats(context: Context): StorageStats {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        return StorageStats(stat.totalBytes, stat.availableBytes, usedBytes(context))
    }

    fun maxArchiveGb(context: Context): Int {
        val stats = storageStats(context)
        return ((stats.freeBytes + stats.recordingBytes - RESERVED_FREE_BYTES) / GB)
            .coerceAtLeast(1).coerceAtMost(512).toInt()
    }

    @Synchronized fun enforceLimit(context: Context, config: RecorderConfig) {
        val limit = config.storageLimitGb * GB
        val deletable = list(context).filterNot(RecordingFile::protected).sortedBy(RecordingFile::modifiedAt)
        val stats = storageStats(context)
        var used = stats.recordingBytes
        var available = stats.freeBytes
        for (recording in deletable) {
            if (used <= limit && available >= RESERVED_FREE_BYTES) break
            if (delete(context, recording)) {
                used -= recording.sizeBytes
                available += recording.sizeBytes
            }
        }
    }

    @Synchronized fun setProtected(context: Context, recording: RecordingFile, protect: Boolean): Boolean {
        if (recording.protected == protect) return true
        val path = if (protect) "$rootPath$PROTECTED/" else cameraPath(recording.inputId)
        return context.contentResolver.update(Uri.parse(recording.uri), ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        }, null, null) > 0
    }

    @Synchronized fun delete(context: Context, recording: RecordingFile): Boolean =
        context.contentResolver.delete(Uri.parse(recording.uri), null, null) > 0

    fun removeInterrupted(context: Context) {
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
            arrayOf("$rootPath%"), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                context.contentResolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
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
                target = createSegment(context, inputId, source.name, source.parentFile?.name == PROTECTED)
                ParcelFileDescriptor.AutoCloseOutputStream(target!!.descriptor).use { output ->
                    source.inputStream().use { it.copyTo(output) }
                }
                finishSegment(context, target!!)
                source.delete()
            }.onFailure { target?.let { abortSegment(context, it) } }
        }
    }

    private fun cameraPath(inputId: Int) = "$rootPath${"camera_$inputId"}/"
}
