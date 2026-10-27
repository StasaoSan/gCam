package com.stasao.gcam

/** A mounted volume must remain available across several polls before recording starts. */
internal class UsbReadinessTracker(
    private val minimumStableMs: Long,
    private val minimumSamples: Int
) {
    private var firstReadyMs = -1L
    private var readySamples = 0

    fun observe(nowMs: Long, ready: Boolean): Boolean {
        if (!ready) {
            firstReadyMs = -1L
            readySamples = 0
            return false
        }
        if (firstReadyMs < 0L || nowMs < firstReadyMs) {
            firstReadyMs = nowMs
            readySamples = 0
        }
        readySamples++
        return readySamples >= minimumSamples && nowMs - firstReadyMs >= minimumStableMs
    }
}
