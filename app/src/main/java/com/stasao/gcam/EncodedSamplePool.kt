package com.stasao.gcam

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** An owned compressed payload. Only the writer may close it, after its sink returns. */
class EncodedSamplePayload internal constructor(
    private val writable:ByteBuffer,
    val size:Int,
    private val release:()->Unit,
):AutoCloseable {
    private val released=AtomicBoolean()
    init {require(size>=0 && writable.capacity()>=size)}
    val buffer:ByteBuffer get() {
        check(!released.get()) {"Compressed sample lease is already released"}
        return writable.asReadOnlyBuffer().apply {position(0);limit(size)}
    }
    internal fun copyFrom(source:ByteBuffer,offset:Int) {
        check(!released.get())
        val slice=source.duplicate().apply {position(offset);limit(offset+size)}
        writable.duplicate().apply {clear();limit(size)}.put(slice)
    }
    override fun close() {if(released.compareAndSet(false,true))release()}
}

interface EncodedSampleStorage {
    fun acquire(channel:Int,size:Int):EncodedSamplePayload?
}

/** Legacy/test storage. Recording sessions explicitly inject their shared native pool. */
object HeapEncodedSampleStorage:EncodedSampleStorage {
    override fun acquire(channel:Int,size:Int):EncodedSamplePayload =
        EncodedSamplePayload(ByteBuffer.wrap(ByteArray(size)),size) {}
}

/** Injectable only at the native boundary, keeping ownership/admission logic real in tests. */
interface EncodedSampleNativeBackend {
    val available:Boolean
    fun create(maxBytes:Long):Long
    fun acquire(pool:Long,channel:Int,size:Int):Long
    fun buffer(pool:Long,lease:Long):ByteBuffer?
    fun release(pool:Long,lease:Long):Boolean
    fun setBudget(pool:Long,bytes:Long)
    fun trim(pool:Long,keepFreeBytes:Long)
    fun snapshot(pool:Long):LongArray
    fun destroy(pool:Long):Boolean
}

private object JniEncodedSampleBackend:EncodedSampleNativeBackend {
    override val available get()=NativeSampleMemory.available
    override fun create(maxBytes:Long)=NativeSampleMemory.create(maxBytes)
    override fun acquire(pool:Long,channel:Int,size:Int)=NativeSampleMemory.acquire(pool,channel,size)
    override fun buffer(pool:Long,lease:Long)=NativeSampleMemory.buffer(pool,lease)
    override fun release(pool:Long,lease:Long)=NativeSampleMemory.release(pool,lease)
    override fun setBudget(pool:Long,bytes:Long)=NativeSampleMemory.setBudget(pool,bytes)
    override fun trim(pool:Long,keepFreeBytes:Long)=NativeSampleMemory.trim(pool,keepFreeBytes)
    override fun snapshot(pool:Long)=NativeSampleMemory.snapshot(pool)
    override fun destroy(pool:Long)=NativeSampleMemory.destroy(pool)
}

/** One session owns one pool; allocated native capacity, including reusable slabs, is capped. */
class EncodedSamplePool(
    maxBytes:Long=REQUESTED_MAX_BYTES,
    activeChannels:Set<Int> =setOf(0,1,2,3),
    private val softShareBytes:Long=125_000_000L,
    private val reserveBytes:Long=16_000_000L,
    private val backend:EncodedSampleNativeBackend=JniEncodedSampleBackend,
):EncodedSampleStorage,AutoCloseable {
    data class Snapshot(val budgetBytes:Long,val allocatedCapacityBytes:Long,val leasedPayloadBytes:Long,
        val liveLeases:Long,val freeCapacityBytes:Long,val closed:Boolean)
    private val lock=Any()
    private val channels=activeChannels.toIntArray().also {require(it.isNotEmpty() && it.all {channel->channel in 0..3})}
    private val active=BooleanArray(4).also {mask->for(channel in channels)mask[channel]=true}
    // Release must not allocate boxed map keys/values while recovering from memory pressure.
    private val channelBytes=LongArray(4)
    private var budget=maxBytes
    private var leasedBytes=0L
    private var liveLeases=0L
    private var closing=false
    private var handle:Long
    val available:Boolean
    init {
        require(maxBytes in 1..REQUESTED_MAX_BYTES)
        require(softShareBytes>0 && reserveBytes>=0)
        handle=if(backend.available)runCatching {backend.create(maxBytes)}.getOrDefault(0L) else 0L
        available=handle!=0L
    }

    override fun acquire(channel:Int,size:Int):EncodedSamplePayload?=synchronized(lock) {
        if(closing || handle==0L || size<=0 || channel !in 0..3 || !active[channel])return@synchronized null
        val current=channelBytes[channel]
        if(size.toLong()>budget-leasedBytes)return@synchronized null
        val share=minOf(softShareBytes,budget/channels.size)
        val reservation=minOf(reserveBytes,share)
        var protectedBytes=0L
        for(peer in channels) {
            if(peer!=channel)protectedBytes+=(reservation-channelBytes[peer]).coerceAtLeast(0L)
        }
        if(size.toLong()>budget-leasedBytes-protectedBytes)return@synchronized null
        var lease=0L
        try {
            lease=backend.acquire(handle,channel,size)
            if(lease==0L)return@synchronized null
            val data=backend.buffer(handle,lease)
            if(data==null || !data.isDirect || data.isReadOnly || data.capacity()<size) {
                backend.release(handle,lease)
                return@synchronized null
            }
            val ownedLease=lease
            val payload=EncodedSamplePayload(data,size) {release(channel,ownedLease,size)}
            channelBytes[channel]=current+size
            leasedBytes+=size;liveLeases++
            payload
        }catch(_:Throwable) {
            if(lease!=0L)runCatching {backend.release(handle,lease)}
            null
        }
    }

    fun setBudget(bytes:Long)=synchronized(lock) {
        require(bytes in 0..REQUESTED_MAX_BYTES)
        budget=bytes
        if(handle!=0L)backend.setBudget(handle,bytes)
    }

    fun trim(keepFreeBytes:Long)=synchronized(lock) {
        require(keepFreeBytes>=0)
        if(handle!=0L)backend.trim(handle,keepFreeBytes)
    }

    fun snapshot():Snapshot=synchronized(lock) {
        if(handle==0L)Snapshot(budget,0,0,0,0,true)
        else backend.snapshot(handle).let {Snapshot(it[0],it[1],it[2],it[3],it[4],false)}
    }

    /** Completion is deferred while any blocked sink or accepted queue owns a payload. */
    override fun close()=synchronized(lock) {
        closing=true
        destroyIfUnused()
    }

    private fun release(channel:Int,lease:Long,size:Int)=synchronized(lock) {
        if(handle==0L)return@synchronized
        try {backend.release(handle,lease)}
        finally {
            channelBytes[channel]-=size
            leasedBytes-=size;liveLeases--
            destroyIfUnused()
        }
    }

    private fun destroyIfUnused() {
        if(closing && liveLeases==0L && handle!=0L && backend.destroy(handle))handle=0L
    }

    companion object {const val REQUESTED_MAX_BYTES=500_000_000L}
}
