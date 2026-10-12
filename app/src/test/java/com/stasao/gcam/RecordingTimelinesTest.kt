package com.stasao.gcam

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimelinesTest {
    private fun file(name: String, camera: Int, durationMs: Long = 180_000) =
        RecordingFile(name, name, camera, 1_000, 0, false, durationMs)

    @Test fun adjacentSegmentsShareTimeline() {
        val files = listOf(
            file("cam0_20261012_120000_000_s42.mp4", 0),
            file("cam0_20261012_120300_000_s42.mp4", 0),
            file("cam1_20261012_120300_000_s42.mp4", 1)
        )
        val groups = RecordingTimelines.group(files)
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.inputId == 0 }.parts.size)
        assertEquals(360_000L, groups.first { it.inputId == 0 }.durationMs)
    }

    @Test fun gapOrDifferentSessionStartsNewTimeline() {
        val files = listOf(
            file("cam0_20261012_120000_000_s42.mp4", 0),
            file("cam0_20261012_120310_000_s42.mp4", 0),
            file("cam0_20261012_120610_000_s43.mp4", 0)
        )
        assertEquals(3, RecordingTimelines.group(files).size)
    }

    @Test fun oldNamesStillGroupByTime() {
        val files = listOf(
            file("cam2_20261012_120000_000.mp4", 2),
            file("cam2_20261012_120300_000.mp4", 2)
        )
        assertEquals(1, RecordingTimelines.group(files).size)
    }
}
