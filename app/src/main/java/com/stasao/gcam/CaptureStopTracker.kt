package com.stasao.gcam

/** Only an explicitly inactive capture that has already produced video uses the fast path. */
internal class CaptureStopTracker {
    private var stoppedSince: Long? = null

    fun observe(nowMs: Long, captureRunning: Boolean?, outputFrames: Long, outputAgeMs: Long): Boolean {
        if (captureRunning != false || outputFrames <= 0 || outputAgeMs < 3_000L) {
            stoppedSince = null
            return false
        }
        val since = stoppedSince?.takeIf { it <= nowMs } ?: nowMs.also { stoppedSince = it }
        return nowMs - since >= 3_000L
    }
}
