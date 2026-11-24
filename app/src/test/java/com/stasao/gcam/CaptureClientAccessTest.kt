package com.stasao.gcam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureClientAccessTest {
    @Test fun reverseRearPreviewHasAnIndependentSlotForBlindAndPanel() {
        for (client in listOf(CaptureClientAccess.Client.BLIND, CaptureClientAccess.Client.PANEL)) {
            assertTrue(CaptureClientAccess.canUseHudSlot(client, false, 13))
        }
        for (client in listOf(CaptureClientAccess.Client.ZONES, CaptureClientAccess.Client.PROJECTION, CaptureClientAccess.Client.UNKNOWN)) {
            assertFalse(CaptureClientAccess.canUseHudSlot(client, false, 13))
        }
    }

    private val panel = CaptureClientAccess.clientFor(listOf("com.stasao.gpanel"))
    private val blind = CaptureClientAccess.clientFor(listOf("com.stasao.gblind"))
    private val projection = CaptureClientAccess.clientFor(listOf("com.g992.anhud"))
    private val zones = CaptureClientAccess.clientFor(listOf("com.stasao.gzones"))

    @Test fun unifiedPanelCanPrepareProjectionAndReportBlindHealth() {
        assertTrue(CaptureClientAccess.canManageProjection(panel))
        assertTrue(CaptureClientAccess.canReportBlindHealth(panel))
    }

    @Test fun unifiedPanelCanOpenBothProjectionCamerasAndTheirEditor() {
        for (slot in listOf(0, 1, 4)) {
            assertTrue("Projection slot $slot", CaptureClientAccess.canUseHudSlot(panel, false, slot))
        }
    }

    @Test fun unifiedPanelRetainsClusterCamerasAndLargePreview() {
        for (slot in listOf(2, 3, 5, 10, 11)) {
            assertTrue("Cluster slot $slot", CaptureClientAccess.canUseHudSlot(panel, false, slot))
        }
    }

    @Test fun embeddedPanelCanUseItsOwnRecorderOverlayChannels() {
        for (slot in listOf(0, 1, 4, 6, 7, 8, 9, 12)) {
            assertTrue("Own slot $slot", CaptureClientAccess.canUseHudSlot(panel, true, slot))
        }
    }

    @Test fun externalPanelCannotControlRecorderOverlayOrZonesExtraStream() {
        for (slot in listOf(6, 7, 8, 9, 12)) {
            assertFalse("External slot $slot", CaptureClientAccess.canUseHudSlot(panel, false, slot))
        }
    }

    @Test fun standaloneBlindCannotControlProjectionLifecycleOrEditor() {
        assertFalse(CaptureClientAccess.canManageProjection(blind))
        assertTrue(CaptureClientAccess.canReportBlindHealth(blind))
        for (slot in listOf(0, 1, 4, 6, 9, 12)) {
            assertFalse("Blind slot $slot", CaptureClientAccess.canUseHudSlot(blind, false, slot))
        }
        for (slot in listOf(2, 3, 5, 10, 11)) {
            assertTrue("Blind slot $slot", CaptureClientAccess.canUseHudSlot(blind, false, slot))
        }
    }

    @Test fun standaloneProjectionRetainsItsStreamsWithoutBlindHealthOrLargePreview() {
        assertTrue(CaptureClientAccess.canManageProjection(projection))
        assertFalse(CaptureClientAccess.canReportBlindHealth(projection))
        for (slot in listOf(0, 1, 2, 3, 4, 5)) {
            assertTrue("ANHUD slot $slot", CaptureClientAccess.canUseHudSlot(projection, false, slot))
        }
        for (slot in listOf(6, 9, 10, 11, 12)) {
            assertFalse("ANHUD slot $slot", CaptureClientAccess.canUseHudSlot(projection, false, slot))
        }
    }

    @Test fun zonesRemainsRestrictedToItsPreviewSlots() {
        assertFalse(CaptureClientAccess.canManageProjection(zones))
        assertFalse(CaptureClientAccess.canReportBlindHealth(zones))
        for (slot in 0..9) assertFalse(CaptureClientAccess.canUseHudSlot(zones, false, slot))
        for (slot in 10..12) assertTrue(CaptureClientAccess.canUseHudSlot(zones, false, slot))
    }

    @Test fun unknownExternalPackageNeverGainsCameraAccess() {
        for (packages in listOf(emptyList(), listOf("com.example.camera"), listOf("com.stasao.gpanel.fake"))) {
            val client = CaptureClientAccess.clientFor(packages)
            assertFalse(CaptureClientAccess.canManageProjection(client))
            assertFalse(CaptureClientAccess.canReportBlindHealth(client))
            for (slot in 0..12) assertFalse(CaptureClientAccess.canUseHudSlot(client, false, slot))
        }
    }

    @Test fun invalidSlotsAreRejectedEvenForTheServiceOwner() {
        for (slot in listOf(-1, 14, Int.MAX_VALUE)) {
            assertFalse(CaptureClientAccess.canUseHudSlot(panel, true, slot))
            assertFalse(CaptureClientAccess.canUseHudSlot(panel, false, slot))
        }
    }
}
