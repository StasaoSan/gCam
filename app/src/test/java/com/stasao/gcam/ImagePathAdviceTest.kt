package com.stasao.gcam

import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePathAdviceTest {
    private val ready = ImagePathAdvice.State(
        installed = true, access = true, reportFresh = true, enabled = true,
        connected = true, video = true, error = null,
    )

    @Test fun distinguishesMissingAccessFromNoFrames() {
        assertTrue(ImagePathAdvice.explain("gBlind", ready.copy(access = false)).contains("не разрешён"))
        assertTrue(ImagePathAdvice.explain("gBlind", ready.copy(video = false)).contains("кадры"))
    }

    @Test fun healthySignalPointsToDisplaySettings() {
        assertTrue(ImagePathAdvice.explain("ANHUD", ready).contains("положение окна"))
    }
}
