package com.stasao.gcam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStopTrackerTest {
    @Test fun confirmsStoppedCaptureOnlyAfterOutputHasGoneStale() {
        val tracker = CaptureStopTracker()
        assertFalse(tracker.observe(1_000, false, 100, 1_000))
        assertFalse(tracker.observe(3_000, false, 100, 3_000))
        assertFalse(tracker.observe(5_000, false, 100, 5_000))
        assertTrue(tracker.observe(6_000, false, 100, 6_000))
    }

    @Test fun ignoresStartupAndSlowButActiveCapture() {
        val tracker = CaptureStopTracker()
        assertFalse(tracker.observe(0, false, 0, 30_000))
        assertFalse(tracker.observe(10_000, false, 0, 40_000))
        assertFalse(tracker.observe(20_000, true, 100, 40_000))
    }

    @Test fun resumedCaptureOrMissingStatsResetConfirmation() {
        for (running in listOf(true, null)) {
            val tracker = CaptureStopTracker()
            assertFalse(tracker.observe(0, false, 100, 3_000))
            assertFalse(tracker.observe(2_000, running, 100, 5_000))
            assertFalse(tracker.observe(3_000, false, 100, 6_000))
            assertTrue(tracker.observe(6_000, false, 100, 9_000))
        }
    }

    @Test fun freshOutputResetsConfirmation() {
        val tracker = CaptureStopTracker()
        assertFalse(tracker.observe(0, false, 100, 3_000))
        assertFalse(tracker.observe(2_000, false, 101, 0))
        assertFalse(tracker.observe(5_000, false, 101, 3_000))
        assertTrue(tracker.observe(8_000, false, 101, 6_000))
    }
}
