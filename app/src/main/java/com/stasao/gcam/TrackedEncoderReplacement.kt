package com.stasao.gcam

import java.util.concurrent.ConcurrentMap

/** Serialized lifecycle attempt. A candidate is tracked before native startup can fail.
 * Creation only allocates an unstarted owner; close must be idempotent. A cancelled
 * or failed started candidate remains in its slot for the lifecycle stop/retry. */
internal fun <T:Any> replaceTrackedEncoder(
    slots:ConcurrentMap<Int,T>,inputId:Int,expected:T,isActive:()->Boolean,
    close:(T)->Unit,create:()->T,start:(T)->Unit,
):T? {
    if(!isActive() || slots[inputId]!==expected)return null
    close(expected)
    if(!isActive() || slots[inputId]!==expected)return null
    val candidate=create()
    if(!isActive() || !slots.replace(inputId,expected,candidate)) {
        close(candidate)
        return null
    }
    if(!isActive())return null
    start(candidate)
    return candidate.takeIf {isActive() && slots[inputId]===it}
}

internal fun localEncoderRetryDelayMs(failures:Int):Long=when(failures) {
    1->1_000L
    2->3_000L
    3->5_000L
    else->30_000L
}

internal data class EncoderRecoveryHealth(val inputId:Int,val drainAlive:Boolean,val storageFailed:Boolean,
    val codecFailed:Boolean,val outputFrames:Long,val lastOutputElapsedMs:Long)
internal data class EncoderRecoveryBlock(val reason:Reason,val inputId:Int?=null) {
    enum class Reason {CAPTURE,OWNERSHIP,STORAGE,OTHER_CHANNEL}
}

/** Only the selected failed channel may be unhealthy during an isolated recovery. */
internal fun isolatedEncoderRecoveryBlock(inputId:Int,selected:Set<Int>,states:List<EncoderRecoveryHealth>,
    captureAlive:Boolean,nowMs:Long):EncoderRecoveryBlock? {
    if(!captureAlive)return EncoderRecoveryBlock(EncoderRecoveryBlock.Reason.CAPTURE)
    if(inputId !in selected || states.size!=selected.size || states.map {it.inputId}.toSet()!=selected)
        return EncoderRecoveryBlock(EncoderRecoveryBlock.Reason.OWNERSHIP)
    states.firstOrNull {it.storageFailed}?.let {
        return EncoderRecoveryBlock(EncoderRecoveryBlock.Reason.STORAGE,it.inputId)
    }
    states.firstOrNull {it.inputId!=inputId && (!it.drainAlive || it.codecFailed || it.outputFrames<=0L ||
        nowMs-it.lastOutputElapsedMs>3_000L)}?.let {
        return EncoderRecoveryBlock(EncoderRecoveryBlock.Reason.OTHER_CHANNEL,it.inputId)
    }
    return null
}
