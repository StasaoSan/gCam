package com.stasao.gcam

import android.content.Context
import android.os.Environment
import java.io.File

object RecordingStore {
    private const val ROOT = "gCam"
    private const val PROTECTED = "protected"
    private const val GB = 1024L * 1024L * 1024L

    fun root(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), ROOT).apply { mkdirs() }

    fun cameraDir(context: Context, inputId: Int): File =
        File(root(context), "camera_$inputId").apply { mkdirs() }

    private fun protectedDir(context: Context): File =
        File(root(context), PROTECTED).apply { mkdirs() }

    fun list(context: Context): List<RecordingFile> {
        val base = root(context)
        return base.walkTopDown().filter { it.isFile && it.extension == "mp4" }.map { file ->
            val protected = file.parentFile?.name == PROTECTED
            val input = Regex("cam(\\d)").find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            RecordingFile(file.absolutePath, file.name, input, file.length(), file.lastModified(), protected)
        }.sortedByDescending { it.modifiedAt }.toList()
    }

    fun usedBytes(context: Context): Long = list(context).sumOf(RecordingFile::sizeBytes)

    @Synchronized fun enforceLimit(context: Context, config: RecorderConfig) {
        val root = root(context)
        val limit = config.storageLimitGb * GB
        val reserve = config.reserveGb * GB
        val deletable = list(context).filterNot(RecordingFile::protected).sortedBy(RecordingFile::modifiedAt)
        var used = list(context).sumOf(RecordingFile::sizeBytes)
        var available = root.usableSpace
        for (recording in deletable) {
            if (used <= limit && available >= reserve) break
            val file = File(recording.path)
            val size = file.length()
            if (file.delete()) {
                used -= size
                available += size
            }
        }
    }

    @Synchronized fun setProtected(context: Context, recording: RecordingFile, protect: Boolean): Boolean {
        val source = File(recording.path)
        if (!source.exists() || recording.protected == protect) return source.exists()
        val targetDir = if (protect) protectedDir(context) else cameraDir(context, recording.inputId)
        return source.renameTo(File(targetDir, source.name))
    }

    @Synchronized fun delete(recording: RecordingFile): Boolean = File(recording.path).delete()

    fun removeInterrupted(context: Context) {
        root(context).walkTopDown().filter { it.isFile && it.extension == "tmp" }.forEach(File::delete)
    }
}
