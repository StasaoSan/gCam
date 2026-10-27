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
}
