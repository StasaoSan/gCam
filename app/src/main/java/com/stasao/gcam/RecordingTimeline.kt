package com.stasao.gcam

import java.text.SimpleDateFormat
import java.util.Locale

data class RecordingTimeline(val parts: List<RecordingFile>, val startedAt: Long) {
    val inputId: Int get() = parts.first().inputId
    val durationMs: Long get() = parts.sumOf { it.durationMs }
    val sizeBytes: Long get() = parts.sumOf { it.sizeBytes }
    val protected: Boolean get() = parts.all { it.protected }
}

/** Groups only same-camera MP4s with no meaningful gap, without copying video. */
object RecordingTimelines {
    private val stampPattern = Regex("cam\\d+_(\\d{8}_\\d{6}_\\d{3})(?:_s(\\d+))?\\.mp4")

    fun group(files: List<RecordingFile>): List<RecordingTimeline> {
        val result = mutableListOf<RecordingTimeline>()
        files.groupBy { it.inputId }.forEach { (_, cameraFiles) ->
            var pending = mutableListOf<RecordingFile>()
            var firstStart = 0L
            var previousStart = 0L
            var previousSession: String? = null
            fun flush() {
                if (pending.isNotEmpty()) result += RecordingTimeline(pending.toList(), firstStart)
                pending = mutableListOf()
            }
            cameraFiles.map { file -> Triple(file, startTime(file.name), session(file.name)) }
                .sortedBy { it.second ?: it.first.modifiedAt }
                .forEach { (file, start, sessionId) ->
                    val prior = pending.lastOrNull()
                    val gap = if (prior != null && start != null) start - (previousStart + prior.durationMs) else Long.MAX_VALUE
                    val sameSession = sessionId == null && previousSession == null ||
                        sessionId != null && sessionId == previousSession
                    val contiguous = prior != null && prior.durationMs > 0 && start != null &&
                        sameSession && gap in -2_000L..15_000L
                    if (!contiguous) {
                        flush()
                        firstStart = start ?: file.modifiedAt
                    }
                    pending += file
                    previousStart = start ?: file.modifiedAt
                    previousSession = sessionId
                }
            flush()
        }
        return result.sortedByDescending { it.startedAt }
    }

    private fun startTime(name: String): Long? = stampPattern.matchEntire(name)?.groupValues?.get(1)?.let { stamp ->
        runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply { isLenient = false }.parse(stamp)?.time
        }.getOrNull()
    }

    private fun session(name: String): String? = stampPattern.matchEntire(name)?.groupValues?.getOrNull(2)?.ifEmpty { null }
}
