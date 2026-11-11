package com.stasao.gcam

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UsbStressTestTest {
    private class MemoryFile(val corrupt: Boolean = false, val truncate: Boolean = false,
                             val onSync: () -> Unit = {}) : UsbStressTest.TestFile {
        override val output = ByteArrayOutputStream()
        var syncs = 0
        override fun sync() { syncs++; onSync() }
        override fun openInput(): ByteArrayInputStream {
            val data = output.toByteArray()
            if (corrupt) data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
            return ByteArrayInputStream(if (truncate) data.copyOf(data.size - 1) else data)
        }
        override fun close() = Unit
    }

    @Test fun writesExactlyTheTotalAcrossFourFilesAndVerifiesAllBytes() {
        val files = List(4) { MemoryFile() }
        val progress = mutableListOf<Int>()
        UsbStressTest.run(files, bytes = 2_100_003) { _, percent -> progress += percent }
        assertEquals(2_100_003, files.sumOf { it.output.size() })
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(files.all { it.syncs > 0 })
        assertFalse(files[0].output.toByteArray().contentEquals(files[1].output.toByteArray()))
    }

    @Test fun rejectsDataCorruption() {
        val files = List(4) { MemoryFile(corrupt = it == 2) }
        val error = runCatching { UsbStressTest.run(files, bytes = 4096) }.exceptionOrNull()
        assertTrue(error is UsbTestFailure)
        assertTrue(error!!.message!!.contains("mismatch"))
    }

    @Test fun rejectsTruncatedReads() {
        val error = runCatching {
            UsbStressTest.run(List(4) { MemoryFile(truncate = it == 3) }, bytes = 4096)
        }.exceptionOrNull()
        assertTrue(error is UsbTestFailure)
        assertTrue(error!!.message!!.contains("incomplete"))
    }

    @Test fun deadlineIncludesSyncAndStopsFurtherWork() {
        var time = 0L
        val files = List(4) { MemoryFile(onSync = { time += 60_000 }) }
        val error = runCatching { UsbStressTest.run(files, bytes = 4096, nowMs = { time }) }.exceptionOrNull()
        assertTrue(error is UsbTestFailure)
        assertEquals(1, files.sumOf { it.syncs })
    }

    @Test fun unplugAbortsTestBeforeNextWrite() {
        var checks = 0
        val error = runCatching {
            UsbStressTest.run(List(4) { MemoryFile() }, bytes = 4096,
                checkConnected = { if (++checks == 3) throw UsbTestFailure("unplugged") })
        }.exceptionOrNull()
        assertEquals("unplugged", error?.message)
    }

    @Test fun speedThresholdUsesChosenCameraCountAndBitrate() {
        val result = UsbStressTest.Result(2_000_000.0, 10_000_000.0, 10, 50_000)
        assertTrue(result.hasHeadroom(4, 2))
        assertFalse(result.hasHeadroom(4, 6))
        assertTrue(result.hasHeadroom(1, 6))
        assertEquals(200_000_000L, UsbStressTest.TOTAL_BYTES)
        assertEquals(60_000L, UsbStressTest.DEADLINE_MS)
    }
}
