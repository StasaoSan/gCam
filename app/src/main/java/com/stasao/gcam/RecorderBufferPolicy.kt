package com.stasao.gcam

object RecorderBufferPolicy {
    const val MAX_BYTES=500_000_000L
    const val MIN_BYTES=16_777_216L
    const val RESERVE_BYTES=536_870_912L
    const val MAX_AGE_MS=15*60_000L
    fun budget(availableBytes:Long,leasedBytes:Long,lowMemory:Boolean):Long {
        val headroom=(availableBytes.coerceAtLeast(0)-RESERVE_BYTES).coerceAtLeast(-MAX_BYTES)
        val permitted=(leasedBytes.coerceIn(0,MAX_BYTES)+headroom).coerceIn(MIN_BYTES,MAX_BYTES)
        return if(lowMemory)minOf(permitted,67_108_864L) else permitted
    }
    // UI_HIDDEN (20) is a lifecycle event, not evidence of memory pressure.
    fun trimBudget(level:Int):Long=when {
        level==15 || level>=80 -> MIN_BYTES
        level==10 || level>=60 -> 67_108_864L
        level==5 || level==40 -> 134_217_728L
        else -> MAX_BYTES
    }
    fun liveBacklog(now:Long,lastEncoded:Long,retainedSamples:Int,drainAlive:Boolean,
        writerStopped:Boolean,failed:Boolean):Boolean=drainAlive && !writerStopped && !failed &&
        retainedSamples>0 && lastEncoded>0 && now-lastEncoded in 0..3_000L
}
