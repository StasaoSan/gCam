package com.stasao.gcam

import org.junit.Assert.assertEquals
import org.junit.Test

class GCamRuntimeSettingsTest {
    @Test fun resettingDotsPreservesOverlaySwitchesAndRecordButton() {
        val config = GCamRuntimeConfig(
            autostart = true,
            recordingAutostart = true,
            statusOverlay = true,
            blindStatusOverlay = false,
            hudStatusOverlay = true,
            cameraOverlay = false,
            recordOverlay = true,
            recordOverlayX = 0.37f,
            recordOverlayY = 0.69f,
            recordOverlaySizeDp = 150,
            statusOverlayX = 0f,
            statusOverlayY = 1f,
            blindStatusOverlayX = 0f,
            blindStatusOverlayY = 1f,
            hudStatusOverlayX = 0f,
            hudStatusOverlayY = 1f
        )

        val reset = GCamRuntimeSettings.withDefaultStatusOverlayPositions(config)

        assertEquals(0.95f, reset.statusOverlayX, 0f)
        assertEquals(0.33f, reset.statusOverlayY, 0f)
        assertEquals(0.82f, reset.blindStatusOverlayX, 0f)
        assertEquals(0.33f, reset.blindStatusOverlayY, 0f)
        assertEquals(0.88f, reset.hudStatusOverlayX, 0f)
        assertEquals(0.33f, reset.hudStatusOverlayY, 0f)
        assertEquals(config.copy(
            statusOverlayX = reset.statusOverlayX,
            statusOverlayY = reset.statusOverlayY,
            blindStatusOverlayX = reset.blindStatusOverlayX,
            blindStatusOverlayY = reset.blindStatusOverlayY,
            hudStatusOverlayX = reset.hudStatusOverlayX,
            hudStatusOverlayY = reset.hudStatusOverlayY
        ), reset)
    }
}
