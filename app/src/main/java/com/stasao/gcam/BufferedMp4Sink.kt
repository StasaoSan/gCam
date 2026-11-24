package com.stasao.gcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One writer owns the muxer, descriptor and publication; codec buffers never enter here. */
internal class BufferedMp4Sink(
    private val context:Context,
    private val inputId:Int,
    private val config:RecorderConfig,
    private val sessionId:Long,
    private val format:MediaFormat,
    private val metrics:RecorderStageMetrics,
    private val onWritten:(EncodedSample,Long)->Unit,
    private val onClosed:()->Unit,
):EncodedSampleSink {
    @Volatile var operation="waiting_for_sample"
        private set(value) {operationAtMs=SystemClock.elapsedRealtime();field=value}
    @Volatile var operationAtMs=SystemClock.elapsedRealtime();private set
    @Volatile var segmentUri:String?=null;private set
    @Volatile var rotationAtPtsUs=Long.MAX_VALUE;private set
    private val schedule=SegmentRotationSchedule(config.segmentMinutes*60L*1_000_000L,
        inputId,config.cameraIds,stagger=RecorderHost.embedded(context))
    private var muxer:MediaMuxer?=null
    private var target:RecordingTarget?=null
    private var track=-1
    private var firstPtsUs=-1L
    private var segmentIndex=0
    private var hasSamples=false
    private var damaged=false
    private var finished=false
    private var writtenFrames=0L
    private var writtenBytes=0L
    private var closedSegments=0

    override fun write(sample:EncodedSample)=metrics.inLane(RecorderStageLane.WRITER) {
        check(!finished) {"MP4 writer is already closed"}
        if(sample.size==0)return@inLane
        if(muxer==null && !sample.keyFrame)return@inLane
        if(muxer==null)openSegment(sample.presentationTimeUs)
        if(sample.keyFrame && sample.presentationTimeUs>=rotationAtPtsUs) {
            closeSegment()
            segmentIndex++
            openSegment(sample.presentationTimeUs)
        }
        val info=MediaCodec.BufferInfo().apply {
            set(0,sample.size,sample.presentationTimeUs-firstPtsUs,sample.flags)
        }
        val began=System.nanoTime()
        try {
            stage("muxer_write_sample",RecorderStage.MUXER_WRITE_SAMPLE) {
                requireNotNull(muxer).writeSampleData(track,sample.buffer,info)
            }
        }catch(error:Throwable) {damaged=true;throw error}
        val writeNs=System.nanoTime()-began
        hasSamples=true;writtenFrames++;writtenBytes+=sample.size
        onWritten(sample,writeNs)
    }

    override fun finish()=metrics.inLane(RecorderStageLane.WRITER) {
        if(finished)return@inLane
        finished=true
        closeSegment()
    }

    override fun checkpoint()=metrics.inLane(RecorderStageLane.WRITER) {
        check(!finished) {"MP4 writer is already closed"}
        if(muxer!=null) {
            closeSegment()
            segmentIndex++
        }
    }

    private fun openSegment(ptsUs:Long) {
        val stamp=SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())
        val name="cam${inputId}_${stamp}_s${sessionId}.mp4"
        val segment=stage("create_segment",RecorderStage.SEGMENT_CREATE) {
            RecordingStore.createSegment(context,inputId,name,storage=config.storage)
        }
        var created:MediaMuxer?=null
        var transferred=false
        try {
            target=segment;segmentUri=segment.uri.toString()
            runCatching {RecorderDiagnostics.record(context,"segment_open input=$inputId session=$sessionId storage=${config.storage}")}
            created=stage("muxer_create_start",RecorderStage.MUXER_CREATE) {
                MediaMuxer(segment.descriptor.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            val newMuxer=requireNotNull(created)
            track=stage("muxer_add_track",RecorderStage.MUXER_ADD_TRACK) {newMuxer.addTrack(MediaFormat(format))}
            stage("muxer_start",RecorderStage.MUXER_START) {newMuxer.start()}
            firstPtsUs=ptsUs;hasSamples=false;damaged=false
            val duration=schedule.durationUs(segmentIndex)
            rotationAtPtsUs=if(ptsUs>Long.MAX_VALUE-duration)Long.MAX_VALUE else ptsUs+duration
            // The writer owns a complete initialized segment only after every setup step succeeds.
            muxer=newMuxer;transferred=true
        }finally {
            if(!transferred) {
                val failedOperation=operation
                runCatching {stage("muxer_release",RecorderStage.MUXER_RELEASE) {created?.release()}}
                runCatching {stage("abort_incomplete_segment",RecorderStage.SEGMENT_ABORT) {RecordingStore.abortSegment(context,segment)}}
                // Even failed instrumentation/publication cleanup must relinquish the descriptor.
                runCatching {segment.descriptor.close()}
                target=null;segmentUri=null;track=-1;firstPtsUs=-1;rotationAtPtsUs=Long.MAX_VALUE
                operation=failedOperation
            }
        }
    }

    private fun closeSegment() {
        val active=muxer ?: return
        val segment=target
        // Detach ownership first: completion is idempotent even if stop/sync/publication fails.
        muxer=null;target=null;segmentUri=null;rotationAtPtsUs=Long.MAX_VALUE
        var failure:Throwable?=null
        var failedOperation=""
        try {stage("muxer_stop",RecorderStage.MUXER_STOP) {active.stop()}}
        catch(error:Throwable) {
            failure=error;failedOperation=operation
            runCatching {RecorderDiagnostics.recordException(context,
                "segment_muxer_stop_error input=$inputId outputFrames=$writtenFrames type=${error.javaClass.name} message=${error.message}",error)}
        }
        try {stage("muxer_release",RecorderStage.MUXER_RELEASE) {active.release()}}
        catch(error:Throwable) {
            if(failure==null){failure=error;failedOperation=operation} else suppress(failure,error)
            runCatching {RecorderDiagnostics.recordException(context,"segment_muxer_release_error input=$inputId type=${error.javaClass.name} message=${error.message}",error)}
        }
        try {
            if(segment!=null) {
                if(hasSamples && !damaged && failure==null) {
                    try {stage("finish_segment",RecorderStage.SEGMENT_FINALIZE) {RecordingStore.finishSegment(context,segment)}}
                    catch(error:Throwable) {
                        failure=error;failedOperation=operation
                        runCatching {stage("abort_incomplete_segment",RecorderStage.SEGMENT_ABORT) {RecordingStore.abortSegment(context,segment)}}
                            .onFailure {suppress(error,it)}
                    }
                    if(failure==null) {
                        closedSegments++;onClosed()
                        runCatching {RecorderDiagnostics.record(context,"segment_closed input=$inputId segments=$closedSegments outputFrames=$writtenFrames outputBytes=$writtenBytes")}
                    }
                }else {
                    stage("abort_incomplete_segment",RecorderStage.SEGMENT_ABORT) {RecordingStore.abortSegment(context,segment)}
                    runCatching {RecorderDiagnostics.record(context,"segment_discarded input=$inputId reason=${if(damaged)"muxer_write_failed" else if(failure!=null)"muxer_stop_failed" else "empty"}")}
                }
            }
        }catch(error:Throwable) {
            if(failure==null){failure=error;failedOperation=operation} else suppress(failure,error)
        }
        finally {
            try {segment?.descriptor?.close()}
            catch(error:Throwable) {
                if(failure==null){failure=error;failedOperation=operation} else suppress(failure,error)
            }
            track=-1;firstPtsUs=-1;hasSamples=false;damaged=false
        }
        failure?.let {operation=failedOperation;throw it}
    }

    private fun suppress(primary:Throwable,secondary:Throwable) {
        if(primary!==secondary)try {primary.addSuppressed(secondary)}catch(_:Throwable) {}
    }

    private inline fun <T> stage(name:String,value:RecorderStage,action:()->T):T {
        operation=name
        return RecorderStageMetrics.measure(value,action)
    }
}
