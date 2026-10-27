package com.stasao.gcam

/** Space needed for the next set of segments, including the free-space reserve. */
internal object ArchiveRetention {
    private const val GB = 1024L * 1024 * 1024
    private const val RESERVE_BYTES = 2 * GB

    fun bytesToFree(config: RecorderConfig, usedBytes: Long, freeBytes: Long): Long? {
        // An unavailable volume must not cause the entire archive to be deleted.
        if (freeBytes < 0) return null
        val segmentBudget = (config.bitrateMbps.toLong() * 1_000_000L / 8 *
            config.cameraIds.size.coerceAtLeast(1) * config.segmentMinutes * 60 * 12 / 10)
            .coerceAtLeast(32L * 1024 * 1024)
        return maxOf(0, usedBytes + segmentBudget - config.storageLimitGb * GB,
            RESERVE_BYTES + segmentBudget - freeBytes)
    }

    fun enforce(files: List<RecordingFile>, config: RecorderConfig, usedBytes: Long,
                freeBytes: Long, delete: (RecordingFile) -> Boolean): ArchiveMaintenance {
        var used = usedBytes
        var available = freeBytes
        var deleted = 0
        val deletable = files.filter { it.storage == config.storage && !it.protected }
            .sortedBy(RecordingFile::modifiedAt)
        for (file in deletable) {
            val needed = bytesToFree(config, used, available) ?: break
            if (needed == 0L) break
            if (delete(file)) {
                used -= file.sizeBytes.coerceAtLeast(0)
                available += file.sizeBytes.coerceAtLeast(0)
                deleted++
            }
        }
        return ArchiveMaintenance(deleted, used, available, bytesToFree(config, used, available) == 0L)
    }
}

internal class ArchiveAccessRequiredException(cause: SecurityException) :
    IllegalStateException(MESSAGE, cause) {
    companion object {
        const val MESSAGE = "Android запретил удаление старых записей. Выдайте gCam доступ ко всем файлам в блоке «Автоматическая очистка архива», затем запустите запись снова."
    }
}
