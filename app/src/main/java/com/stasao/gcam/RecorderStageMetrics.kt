package com.stasao.gcam

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class RecorderStageLane { DRAIN, LIFECYCLE, WRITER }
enum class RecorderStage {
    CODEC_CREATE, CODEC_CONFIGURE, CODEC_INPUT_SURFACE, CODEC_START,
    CAPTURE_ATTACH, CAPTURE_DETACH, CODEC_SIGNAL_END, DRAIN_JOIN,
    CODEC_STOP, CODEC_RELEASE, SURFACE_RELEASE, CODEC_DEQUEUE_OUTPUT,
    CODEC_OUTPUT_FORMAT, CODEC_GET_OUTPUT, CODEC_RELEASE_OUTPUT, CODEC_REQUEST_SYNC,
    SEGMENT_CREATE, STORAGE_VALIDATE, MEDIA_INSERT, DESCRIPTOR_OPEN,
    MUXER_CREATE, MUXER_ADD_TRACK, MUXER_START, MUXER_WRITE_SAMPLE,
    MUXER_STOP, MUXER_RELEASE, SEGMENT_FINALIZE, SEGMENT_ABORT, SEGMENT_STAT, DESCRIPTOR_SYNC,
    DESCRIPTOR_CLOSE, MEDIA_PUBLISH, MEDIA_DELETE,
}
data class RecorderActiveStage(val lane:RecorderStageLane,val stage:RecorderStage,val ageMs:Long,val depth:Int)
data class RecorderStageCounter(val lane:RecorderStageLane,val stage:RecorderStage,
    val count:Long,val failures:Long,val totalNs:Long,val maxNs:Long,
    val windowCount:Long,val windowFailures:Long,val windowNs:Long,val windowMaxNs:Long)
data class RecorderStageSnapshot(val input:Int,val session:Long,
    val active:List<RecorderActiveStage>,val counters:List<RecorderStageCounter>,val droppedSpans:Long=0,
    val instance:Long=0)
data class RecorderStageBinding(val metrics:RecorderStageMetrics,val lane:RecorderStageLane)

/** Fixed counters and independent lane stacks; no Context, strings, I/O or locks around measured work. */
class RecorderStageMetrics(val input:Int,val session:Long,
    private val clockNs:()->Long=SystemClock::elapsedRealtimeNanos) {
    private class Span {var stage=0;var token=0L;var started=0L}
    private class Counts {
        var count=0L;var failures=0L;var total=0L;var max=0L
        var windowCount=0L;var windowFailures=0L;var windowTotal=0L;var windowMax=0L
    }
    private val lock=Any()
    private val stack=Array(RecorderStageLane.entries.size){Array(8){Span()}}
    private val depths=IntArray(RecorderStageLane.entries.size)
    private val rows=Array(RecorderStageLane.entries.size*RecorderStage.entries.size){Counts()}
    private var serial=0L
    private var dropped=0L
    val instance=instances.incrementAndGet()
    fun begin(lane:RecorderStageLane,stage:RecorderStage):Long=synchronized(lock) {
        val index=lane.ordinal;val depth=depths[index]
        if(depth==stack[index].size){dropped++;return@synchronized 0L}
        val span=stack[index][depth];span.stage=stage.ordinal;span.token=++serial;span.started=clockNs()
        depths[index]=depth+1;span.token
    }
    fun end(lane:RecorderStageLane,token:Long,failed:Boolean=false) {
        if(token==0L)return
        synchronized(lock) {
            val index=lane.ordinal;val depth=depths[index]
            if(depth==0 || stack[index][depth-1].token!=token){dropped++;return}
            val span=stack[index][depth-1];val elapsed=(clockNs()-span.started).coerceAtLeast(0)
            val row=rows[index*RecorderStage.entries.size+span.stage]
            row.count++;row.windowCount++
            if(failed){row.failures++;row.windowFailures++}
            row.total=saturatedAdd(row.total,elapsed);row.windowTotal=saturatedAdd(row.windowTotal,elapsed)
            row.max=maxOf(row.max,elapsed);row.windowMax=maxOf(row.windowMax,elapsed)
            depths[index]=depth-1
        }
    }
    fun snapshot(nowNs:Long=clockNs(),resetWindow:Boolean=false):RecorderStageSnapshot=synchronized(lock) {
        val active=ArrayList<RecorderActiveStage>(4)
        for(lane in RecorderStageLane.entries)for(depth in 0 until depths[lane.ordinal]) {
            val span=stack[lane.ordinal][depth]
            active+=RecorderActiveStage(lane,RecorderStage.entries[span.stage],
                ((nowNs-span.started).coerceAtLeast(0)/1_000_000),depth)
        }
        val counters=ArrayList<RecorderStageCounter>()
        for(lane in RecorderStageLane.entries)for(stage in RecorderStage.entries) {
            val row=rows[lane.ordinal*RecorderStage.entries.size+stage.ordinal]
            if(row.count==0L)continue
            counters+=RecorderStageCounter(lane,stage,row.count,row.failures,row.total,row.max,
                row.windowCount,row.windowFailures,row.windowTotal,row.windowMax)
            if(resetWindow){row.windowCount=0;row.windowFailures=0;row.windowTotal=0;row.windowMax=0}
        }
        RecorderStageSnapshot(input,session,active,counters,dropped,instance)
    }
    fun <T> inLane(lane:RecorderStageLane,action:()->T):T {
        val previous=binding.get();binding.set(RecorderStageBinding(this,lane))
        return try {action()} finally {if(previous==null)binding.remove() else binding.set(previous)}
    }
    companion object {
        private val instances=AtomicLong()
        private val binding=ThreadLocal<RecorderStageBinding?>()
        fun currentBinding():RecorderStageBinding?=binding.get()
        inline fun <T> measure(stage:RecorderStage,action:()->T):T {
            val owner=currentBinding() ?: return action()
            val token=owner.metrics.begin(owner.lane,stage)
            var failed=true
            return try {action().also{failed=false}} finally {owner.metrics.end(owner.lane,token,failed)}
        }
        private fun saturatedAdd(first:Long,second:Long)=if(Long.MAX_VALUE-first<second)Long.MAX_VALUE else first+second
    }
}

/** One caller polls this off the frame path; there is no queue and close never waits for I/O. */
class RecorderStageLogSampler(private val clockNs:()->Long=SystemClock::elapsedRealtimeNanos) {
    private var closed=false
    private var lastAt:Long?=null
    @Synchronized fun sample(metrics:Collection<RecorderStageMetrics>):List<String> {
        if(closed)return emptyList()
        val now=clockNs()
        lastAt?.let{if(now<it || now-it<5_000_000_000L)return emptyList()}
        // Four owned inputs plus the one serial lifecycle worker's replacement candidate.
        val selected=metrics.distinct().take(5)
        if(selected.isEmpty())return emptyList()
        val views=selected.map{it.snapshot(now,resetWindow=true)}
        if(views.all{it.active.isEmpty()&&it.counters.isEmpty()})return emptyList()
        lastAt=now
        return views.map(::format)
    }
    @Synchronized fun close(){closed=true}
    companion object {
        fun activeDescription(snapshot:RecorderStageSnapshot)=snapshot.active.joinToString(","){value->
            "${value.lane.name.lowercase(Locale.US)}:${value.stage.name.lowercase(Locale.US)}:ageMs=${value.ageMs}:depth=${value.depth}"
        }.ifEmpty{"idle"}
        fun format(snapshot:RecorderStageSnapshot):String=buildString {
            append("encoder_stages input=").append(snapshot.input).append(" session=").append(snapshot.session)
            append(" instance=").append(snapshot.instance).append(" active=[").append(activeDescription(snapshot)).append(']')
            append(" droppedSpans=").append(snapshot.droppedSpans)
            var omitted=0
            for(row in snapshot.counters) {
                if(row.windowCount==0L)continue
                val value=" | ${row.lane.name.lowercase(Locale.US)}.${row.stage.name.lowercase(Locale.US)}"+
                    " count=${row.count} fail=${row.failures} maxUs=${row.maxNs/1000}"+
                    " winCount=${row.windowCount} winFail=${row.windowFailures}"+
                    " winAvgUs=${if(row.windowCount>0)row.windowNs/row.windowCount/1000 else 0}"+
                    " winMaxUs=${row.windowMaxNs/1000}"
                if(length+value.length>7_850){omitted++;continue}
                append(value)
            }
            if(omitted>0)append(" omittedRows=").append(omitted)
        }
    }
}
