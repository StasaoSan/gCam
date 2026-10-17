package com.stasao.gcam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalRecordingStoreTest {
    @Test fun removableMediaStoreCanCreateAndRemoveSegment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("USB-накопитель не подключён", RecordingStore.selectedDrive(context) != null)
        assertNull(RecordingStore.validateExternal(context))
        val probe = RecordingStore.createSegment(
            context, 0, "cam0_20000101_000000_000_s0.mp4", storage = RecordingStorage.EXTERNAL
        )
        try {
            check(probe.descriptor.fileDescriptor.valid())
        } finally {
            RecordingStore.abortSegment(context, probe)
        }
        val maintenance = RecordingStore.enforceLimit(context, RecorderConfig(
            cameraIds = setOf(0, 1, 2, 3), storage = RecordingStorage.EXTERNAL,
            storageLimitGb = 1000
        ))
        assertTrue(maintenance.ready)
        assertTrue(maintenance.freeBytes > RecordingStore.RESERVED_FREE_BYTES)
    }
}
