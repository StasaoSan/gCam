package com.stasao.gcam

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePathAdviceTest {
    private val ready = ImagePathAdvice.State(
        installed = true, access = true, reportFresh = true, enabled = true,
        connected = true, video = true, error = null,
    )

    @Test fun missingAccessTakesPriorityOverMissingFrames() {
        assertEquals(R.string.gcam_path_access_missing,
            ImagePathAdvice.messageId(ready.copy(access = false, video = false)))
        assertEquals(R.string.gcam_path_frames_missing,
            ImagePathAdvice.messageId(ready.copy(video = false)))
    }

    @Test fun healthySignalPointsToDisplaySettings() {
        assertEquals(R.string.gcam_path_ready, ImagePathAdvice.messageId(ready))
    }

    @Test fun staleStatusTakesPriorityOverPreviousError() {
        assertEquals(R.string.gcam_path_status_missing,
            ImagePathAdvice.messageId(ready.copy(reportFresh = false, error = "old error")))
        assertEquals(R.string.gcam_path_error, ImagePathAdvice.messageId(ready.copy(error = "live error")))
    }
}
