package com.stasao.gcam

import org.junit.Assert.*
import org.junit.Test

class ArchiveRetentionTest {
    private val gb = 1024L * 1024 * 1024
    private val config = RecorderConfig(storage = RecordingStorage.INTERNAL, storageLimitGb = 10,
        cameraIds = setOf(2, 3), bitrateMbps = 3, segmentMinutes = 1)

    private fun clip(id: Int, protected: Boolean = false,
                     storage: RecordingStorage = RecordingStorage.INTERNAL) = RecordingFile(
        "content://media/external_primary/video/media/$id", "cam2_$id.mp4", 2, gb,
        id.toLong(), protected, storage = storage)

    @Test fun fullArchiveDeletesOldestUnprotectedFileAndKeepsOtherDrive() {
        val removed = mutableListOf<Int>()
        val files = listOf(clip(4), clip(1, protected = true), clip(3),
            clip(0, storage = RecordingStorage.EXTERNAL), clip(2))
        val result = ArchiveRetention.enforce(files, config, 10 * gb, 30 * gb) {
            removed += it.modifiedAt.toInt(); true
        }
        assertEquals(listOf(2), removed)
        assertEquals(9 * gb, result.usedBytes)
        assertTrue(result.ready)
    }

    @Test fun permissionDenialDoesNotDeleteNewerFilesAndGrantAllowsRetry() {
        val files = listOf(clip(2), clip(1))
        var accessGranted = false
        val attempts = mutableListOf<Int>()
        val delete: (RecordingFile) -> Boolean = {
            attempts += it.modifiedAt.toInt()
            if (!accessGranted) throw SecurityException("access denied")
            true
        }
        try {
            ArchiveRetention.enforce(files, config, 10 * gb, 30 * gb, delete)
            fail("The permission failure must reach the recorder and be shown to the user")
        } catch (_: SecurityException) { }
        assertEquals(listOf(1), attempts)
        accessGranted = true
        val result = ArchiveRetention.enforce(files, config, 10 * gb, 30 * gb, delete)
        assertEquals(listOf(1, 1), attempts)
        assertEquals(1, result.deletedFiles)
        assertTrue(result.ready)
    }

    @Test fun increasingLimitAvoidsDeletionButDoesNotFixPermission() {
        var attempts = 0
        val result = ArchiveRetention.enforce(listOf(clip(1)), config.copy(storageLimitGb = 20),
            10 * gb, 30 * gb) { attempts++; throw SecurityException("access denied") }
        assertEquals(0, attempts)
        assertTrue(result.ready)
    }

    @Test fun unknownFreeSpaceDoesNotEraseArchive() {
        val result = ArchiveRetention.enforce(listOf(clip(1)), config, 20 * gb, -1) {
            fail("No deletions while the volume cannot report free space"); true
        }
        assertFalse(result.ready)
        assertEquals(0, result.deletedFiles)
    }

    @Test fun unsuccessfulDeletionsDoNotCountAsFreeSpace() {
        val result = ArchiveRetention.enforce(listOf(clip(1)), config, 10 * gb, 30 * gb) { false }
        assertFalse(result.ready)
        assertEquals(10 * gb, result.usedBytes)
        assertEquals(0, result.deletedFiles)
    }

    @Test fun reserveIsPreservedEvenWhenBelowArchiveLimit() {
        val result = ArchiveRetention.enforce(listOf(clip(1), clip(2)), config, 5 * gb, 2 * gb) { true }
        assertTrue(result.ready)
        assertEquals(1, result.deletedFiles)
        assertEquals(3 * gb, result.freeBytes)
    }
}
