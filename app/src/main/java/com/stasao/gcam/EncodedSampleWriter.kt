package com.stasao.gcam

import java.nio.ByteBuffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class EncodedSample(val payload:EncodedSamplePayload,val presentationTimeUs:Long,val flags:Int,val keyFrame:Boolean) {
    constructor(bytes:ByteArray,presentationTimeUs:Long,flags:Int,keyFrame:Boolean):this(
        EncodedSamplePayload(ByteBuffer.wrap(bytes),bytes.size) {},presentationTimeUs,flags,keyFrame)
    val size:Int get()=payload.size
    val buffer:ByteBuffer get()=payload.buffer
}

interface EncodedSampleSink {
    fun write(sample:EncodedSample)
    fun checkpoint()=Unit
    fun finish()
}

class EncodedSampleBackpressureException(message:String):IOException(message)

class EncodedSampleWriter(
    private val sink:EncodedSampleSink,
    private val maxBytes:Long,
    private val maxAgeMs:Long,
    private val maxSamples:Int,
    private val nowMs:()->Long={System.nanoTime()/1_000_000},
    private val onFailure:(Throwable)->Unit={},
    private val threadName:String="gcam-sample-writer",
    private val storage:EncodedSampleStorage=HeapEncodedSampleStorage,
    private val channel:Int=0,
) {
    data class Snapshot(val retainedBytes:Long,val retainedSamples:Int,val queuedBytes:Long,val queuedSamples:Int,
        val inFlightBytes:Int,val oldestAgeMs:Long,val accepted:Long,val written:Long,val rejected:Long,
        val discarded:Long,val maxRetainedBytes:Long,val maxRetainedSamples:Int,val finishing:Boolean,val stopped:Boolean)
    @Volatile var failure:Throwable?=null;private set
    private data class Pending(val sample:EncodedSample,val enqueuedAt:Long)
    private val lock=java.lang.Object()
    private val queue=ArrayDeque<Pending>()
    private val stoppedSignal=CountDownLatch(1)
    private var finishing=false
    @Volatile
    private var stopped=false
    private var accepted=0L
    private var written=0L
    private var rejected=0L
    private var discarded=0L
    private var retainedBytes=0L
    private var retainedSamples=0
    private var peakBytes=0L
    private var peakSamples=0
    private var inFlight:Pending?=null
    private var checkpointAt:Long?=null
    init {
        require(maxBytes>0 && maxAgeMs>0 && maxSamples>0)
        Thread(::runWriter,threadName).apply {isDaemon=true;start()}
    }
    val alive:Boolean get()=!stopped

    /** Admission never performs output storage I/O. Its failure callback must also stay non-blocking. */
    fun offer(buffer:ByteBuffer,offset:Int,size:Int,pts:Long,flags:Int,keyFrame:Boolean):Boolean {
        require(offset>=0 && size>=0 && offset<=buffer.limit() && size<=buffer.limit()-offset)
        var notify:Throwable?=null
        val result=synchronized(lock) {
            if(finishing || stopped){rejected++;return@synchronized false}
            val now=nowMs()
            val oldest=inFlight ?: queue.firstOrNull()
            if(oldest!=null && age(oldest,now)>maxAgeMs) {
                rejected++
                notify=failLocked(EncodedSampleBackpressureException("Compressed sample queue exceeded ${maxAgeMs}ms"))
                return@synchronized false
            }
            if(size.toLong()>maxBytes-retainedBytes || retainedSamples>=maxSamples) {
                rejected++
                notify=failLocked(EncodedSampleBackpressureException("Compressed sample queue capacity exceeded: bytes=$retainedBytes samples=$retainedSamples"))
                return@synchronized false
            }
            if(size==0)return@synchronized true
            var payload:EncodedSamplePayload?=null
            try {
                payload=storage.acquire(channel,size)
                if(payload==null) {
                    rejected++
                    notify=failLocked(EncodedSampleBackpressureException("Compressed sample storage admission failed: channel=$channel size=$size"))
                    return@synchronized false
                }
                payload.copyFrom(buffer,offset)
                queue.addLast(Pending(EncodedSample(payload,pts,flags,keyFrame),now))
                accepted++;retainedBytes+=size;retainedSamples++
                peakBytes=maxOf(peakBytes,retainedBytes);peakSamples=maxOf(peakSamples,retainedSamples)
                lock.notifyAll()
                true
            }catch(error:Throwable) {
                try {payload?.close()}catch(releaseError:Throwable) {suppress(error,releaseError)}
                rejected++;notify=failLocked(error);false
            }
        }
        notify?.let(::notifyFailure)
        return result
    }

    /** Sticky out-of-band completion cannot be lost behind a full data queue. */
    fun finish()=synchronized(lock) {
        finishing=true
        lock.notifyAll()
    }

    /** A sticky barrier marks the current accepted prefix without waiting for a blocked sink. */
    fun checkpoint():Boolean=synchronized(lock) {
        if(finishing || stopped)return@synchronized false
        checkpointAt=maxOf(checkpointAt ?: accepted,accepted)
        lock.notifyAll()
        true
    }

    fun awaitStopped(timeoutMs:Long):Boolean {
        require(timeoutMs>=0)
        return stoppedSignal.await(timeoutMs,TimeUnit.MILLISECONDS)
    }

    fun snapshot():Snapshot=synchronized(lock) {
        val oldest=inFlight ?: queue.firstOrNull()
        val inFlightBytes=inFlight?.sample?.size ?: 0
        Snapshot(retainedBytes,retainedSamples,retainedBytes-inFlightBytes,queue.size,
            inFlightBytes,oldest?.let {age(it,nowMs())} ?: 0L,accepted,written,rejected,discarded,
            peakBytes,peakSamples,finishing,stopped)
    }

    /** Called under the queue lock; only the detector invokes the callback after releasing it. */
    private fun failLocked(error:Throwable):Throwable? {
        finishing=true
        lock.notifyAll()
        if(failure!=null)return null
        failure=error
        return error
    }

    private fun notifyFailure(error:Throwable) {
        // A callback failure must not prevent sink finalization or leak its ownership.
        runCatching {onFailure(error)}
    }

    private fun discardQueuedLocked() {
        while(queue.isNotEmpty()) {
            val pending=queue.removeFirst()
            retainedBytes-=pending.sample.size;retainedSamples--;discarded++
            try {pending.sample.payload.close()}
            catch(error:Throwable) {failure?.let {suppress(it,error)}}
        }
    }

    private fun suppress(primary:Throwable,secondary:Throwable) {
        if(primary!==secondary)try {primary.addSuppressed(secondary)}catch(_:Throwable) {}
    }

    private fun age(pending:Pending,now:Long)=(now-pending.enqueuedAt).coerceAtLeast(0L)

    private fun runWriter() {
        try {
            while(true) {
                var checkpoint=false
                val pending=synchronized(lock) {
                    while(queue.isEmpty() && !finishing && checkpointAt==null)lock.wait()
                    val barrier=checkpointAt
                    if(barrier!=null && written>=barrier) {
                        checkpointAt=null;checkpoint=true
                        null
                    }else if(queue.isEmpty())null
                    else queue.removeFirst().also {inFlight=it}
                }
                if(checkpoint) {
                    try {sink.checkpoint()}
                    catch(error:Throwable) {
                        val first=synchronized(lock) {
                            val captured=failLocked(error)
                            checkpointAt=null;discardQueuedLocked()
                            captured
                        }
                        first?.let(::notifyFailure)
                    }
                    continue
                }
                if(pending==null)break
                try {
                    sink.write(pending.sample)
                    synchronized(lock){written++}
                }catch(error:Throwable) {
                    val first=synchronized(lock) {
                        val captured=failLocked(error)
                        // A failed write may already be partial. Never feed dependent P-frames after it.
                        checkpointAt=null
                        discardQueuedLocked()
                        captured
                    }
                    first?.let(::notifyFailure)
                }finally {
                    try {pending.sample.payload.close()}
                    finally {
                        synchronized(lock) {
                            retainedBytes-=pending.sample.size;retainedSamples--
                            inFlight=null
                        }
                    }
                }
            }
        }catch(error:Throwable) {
            val first=synchronized(lock){val captured=failLocked(error);discardQueuedLocked();captured}
            first?.let(::notifyFailure)
        }finally {
            try {sink.finish()}
            catch(error:Throwable) {
                synchronized(lock){failLocked(error)}?.let(::notifyFailure)
            }finally {
                synchronized(lock){finishing=true;stopped=true}
                stoppedSignal.countDown()
            }
        }
    }
}
