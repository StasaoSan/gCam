package com.stasao.gcam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReadinessTrackerTest {
    @Test fun requiresThreeSamplesAcrossSixSeconds() {
        val tracker = UsbReadinessTracker(6_000, 3)
        assertFalse(tracker.observe(10_000, true))
        assertFalse(tracker.observe(13_000, true))
        assertTrue(tracker.observe(16_000, true))
    }

    @Test fun disconnectResetsTheStableWindow() {
        val tracker = UsbReadinessTracker(6_000, 3)
        assertFalse(tracker.observe(10_000, true))
        assertFalse(tracker.observe(13_000, true))
        assertFalse(tracker.observe(15_000, false))
        assertFalse(tracker.observe(16_000, true))
        assertFalse(tracker.observe(19_000, true))
        assertTrue(tracker.observe(22_000, true))
    }

    @Test fun anotherDriveCannotCompleteThePreviousDrivesWindow() {
        val tracker = UsbReadinessTracker(6_000, 3)
        assertFalse(tracker.observe(0, true, "A"))
        assertFalse(tracker.observe(3_000, true, "A"))
        assertFalse(tracker.observe(6_000, true, "B"))
        assertFalse(tracker.observe(9_000, true, "B"))
        assertTrue(tracker.observe(12_000, true, "B"))
    }

    @Test fun failedWriteProbeRestartsRecoveryStabilityWindow() {
        val tracker = UsbReadinessTracker(15_000, 3)
        assertFalse(tracker.observe(0, true, "A"))
        assertFalse(tracker.observe(12_000, true, "A"))
        assertFalse(tracker.observe(15_000, false, "A"))
        assertFalse(tracker.observe(18_000, true, "A"))
        assertFalse(tracker.observe(30_000, true, "A"))
        assertTrue(tracker.observe(33_000, true, "A"))
    }
}
