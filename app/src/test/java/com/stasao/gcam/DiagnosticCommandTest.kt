package com.stasao.gcam

import org.junit.Assert.*
import org.junit.Test

class DiagnosticCommandTest {
    @Test fun capsOutputAndDrainsPipeWithoutDeadlocking() {
        val result = DiagnosticCommand.run(listOf("sh", "-c",
            "i=0; while [ \"\u0024i\" -lt 10000 ]; do echo storage-diagnostic-line; i=\u0024((i+1)); done"),
            maxChars = 1024)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals(1024, result.output.length)
    }

    @Test fun hungCommandHasDeadline() {
        val started = System.nanoTime()
        val result = DiagnosticCommand.run(listOf("sh", "-c", "exec sleep 10"), timeoutMs = 100)
        assertTrue(result.timedOut)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }
}
