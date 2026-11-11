package com.stasao.gcam

/** A mounted volume must remain available across several polls before recording starts. */
internal class UsbReadinessTracker(
    private val minimumStableMs: Long,
    private val minimumSamples: Int
) {
    private var firstReadyMs = -1L
    private var readySamples = 0
    private var readyIdentity: String? = null

    fun observe(nowMs: Long, ready: Boolean, identity: String? = null): Boolean {
        if (!ready) {
            firstReadyMs = -1L
            readySamples = 0
            readyIdentity = null
            return false
        }
        if (firstReadyMs < 0L || nowMs < firstReadyMs || readyIdentity != identity) {
            firstReadyMs = nowMs
            readySamples = 0
            readyIdentity = identity
        }
        readySamples++
        return readySamples >= minimumSamples && nowMs - firstReadyMs >= minimumStableMs
    }
}
