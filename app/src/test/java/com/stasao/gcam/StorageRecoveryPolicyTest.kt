package com.stasao.gcam

import org.junit.Assert.*
import org.junit.Test

class StorageRecoveryPolicyTest {
    @Test fun recognizesErrorsFromTheUsbIncidentWithoutCallingThemCodecFailures() {
        listOf("rename failed: EIO (I/O error)", "sync failed",
            "writeSampleData returned an error", "Volume 6745-2301 not found")
            .forEach { assertTrue(it, StorageRecoveryPolicy.isStorageFailure(it)) }
        assertFalse(StorageRecoveryPolicy.isStorageFailure("codec_dequeue_output IllegalStateException"))
        assertFalse(StorageRecoveryPolicy.isStorageFailure("Нет записанных кадров 30 секунд"))
    }

    @Test fun repeatedFailuresBackOffButDoNotDisableRecording() {
        assertEquals(10_000L, StorageRecoveryPolicy.delayMs(1))
        assertEquals(20_000L, StorageRecoveryPolicy.delayMs(2))
        assertEquals(40_000L, StorageRecoveryPolicy.delayMs(3))
        assertEquals(60_000L, StorageRecoveryPolicy.delayMs(6))
        assertEquals(60_000L, StorageRecoveryPolicy.delayMs(100))
    }
}
