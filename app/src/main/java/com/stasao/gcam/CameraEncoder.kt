package com.stasao.gcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.DeadObjectException
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import java.nio.ByteBuffer

class CameraEncoder(
    private val context: Context,
    val inputId: Int,
    private val config: RecorderConfig,
    private val sessionId: Long,
    private val samplePool: EncodedSamplePool? = null,
    private val onError: (String) -> Unit
) {
    private val diagnosticStages=RecorderStageMetrics(inputId,sessionId)
    fun diagnosticSnapshot()=diagnosticStages.snapshot()
    internal fun diagnosticMetrics()=diagnosticStages
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val endOfInputRequested = AtomicBoolean(false)
    private val codecReleased = AtomicBoolean(false)
    private var captureOutputRequested = false
    private var stopCompleted = false
    private var codecName = "uncreated"
    private var codecCreatedAtMs = 0L
    private var codecStartedAtMs = 0L
    @Volatile private var lastOutputPtsUs = -1L
    private lateinit var codec: MediaCodec
    private lateinit var inputSurface: Surface
    private var drainThread: Thread? = null
    private val bufferedRecording=samplePool!=null || RecorderHost.embedded(context)
    private val checkpointSync=AtomicBoolean()
    @Volatile var lastEncodedElapsedMs=0L;private set
    @Volatile private var sampleWriter:EncodedSampleWriter?=null
    @Volatile private var sampleSink:BufferedMp4Sink?=null
    private val bufferedFailure=AtomicReference<Throwable?>()
    private val bufferedFailureLogged=AtomicBoolean()
    fun writerSnapshot()=sampleWriter?.snapshot()
    fun hasLiveBufferedBacklog(now:Long):Boolean {
        val writer=sampleWriter ?: return false
        val snapshot=writer.snapshot()
        return RecorderBufferPolicy.liveBacklog(now,lastEncodedElapsedMs,snapshot.retainedSamples,
            drainAlive,snapshot.stopped,storageFailed || codecFailed || writer.failure!=null)
    }
    /** The writer owns the prefix barrier; only the codec thread requests its next IDR. */
    fun requestCheckpoint():Boolean {
        if(stopRequested.get() || sampleWriter?.checkpoint()!=true)return false
        checkpointSync.set(true)
        return true
    }
    val writerOperation:String get()=sampleSink?.operation ?: "not_started"
    val writerOperationStartedElapsedMs:Long get()=sampleSink?.operationAtMs ?: 0L
    @Volatile var outputFrames: Long = 0
        private set
    @Volatile var outputBytes: Long = 0
        private set
    @Volatile var lastOutputElapsedMs: Long = 0
        private set
    @Volatile var completedSegments: Int = 0
        private set
    @Volatile var muxWriteTotalNs: Long = 0
        private set
    @Volatile var muxWriteCount: Long = 0
        private set
    @Volatile var muxWriteMaxNs: Long = 0
        private set
    val drainAlive: Boolean get() = drainThread?.isAlive == true
    @Volatile var storageFailed: Boolean = false
        private set
    @Volatile var codecFailed: Boolean = false
        private set
    @Volatile var failureOperation: String? = null
        private set
    @Volatile var failureMessage: String? = null
        private set

    @Volatile var operation: String = "not_started"
        private set(value) {
            operationStartedElapsedMs = SystemClock.elapsedRealtime()
            field = value
        }
    @Volatile var operationStartedElapsedMs: Long = 0
        private set
    @Volatile var segmentUri: String? = null
        get()=if(bufferedRecording)sampleSink?.segmentUri else field
        private set

    private lateinit var capture: IQCarCamCapture

    @Synchronized fun start(capture: IQCarCamCapture) = diagnosticStages.inLane(RecorderStageLane.LIFECYCLE) {
        startOwned(capture)
    }
    private fun startOwned(capture: IQCarCamCapture) {
        check(!stopCompleted) { "Encoder is already closed" }
        check(running.compareAndSet(false, true))
        this.capture = capture
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
                setInteger(MediaFormat.KEY_PRIORITY, 1) // Background recording yields to live camera/video playback.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            codecCreatedAtMs = SystemClock.elapsedRealtime()
            codec = stage(RecorderStage.CODEC_CREATE) { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            codecName = codec.name
            RecorderDiagnostics.record(context,
                "encoder_config input=$inputId name=${codec.name} hardware=${codec.codecInfo.isHardwareAccelerated} " +
                    "size=${config.width}x${config.height} fps=${config.fps} bitrateMbps=${config.bitrateMbps}")
            stage(RecorderStage.CODEC_CONFIGURE) { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            inputSurface = stage(RecorderStage.CODEC_INPUT_SURFACE) { codec.createInputSurface() }
            stage(RecorderStage.CODEC_START) { codec.start() }
            codecStartedAtMs = SystemClock.elapsedRealtime()
            captureOutputRequested = true
            val nativeStatus = stage(RecorderStage.CAPTURE_ATTACH) { capture.start(inputSurface, inputId, config.fps) }
            check(nativeStatus.contains("принят", ignoreCase = true)) { nativeStatus }
            lastOutputElapsedMs = SystemClock.elapsedRealtime()
            drainThread = Thread(::drain, "gcam-encoder-$inputId").also { it.start() }
        } catch (t: Throwable) {
            running.set(false)
            try {
                detachCapture()
            } catch (detachFailure: Throwable) {
                t.addSuppressed(detachFailure)
                RecorderDiagnostics.recordException(context,
                    "encoder_start_detach_error input=$inputId session=$sessionId name=$codecName", detachFailure)
                // The caller retains this encoder until native output detachment is confirmed.
                throw t
            }
            releaseCodec()
            stopCompleted = true
            throw t
        }
    }

    fun requestStop() {
        stopRequested.set(true)
    }

    @Synchronized fun stop() = diagnosticStages.inLane(RecorderStageLane.LIFECYCLE) { stopOwned() }
    private fun stopOwned() {
        if (stopCompleted) return
        requestStop()
        // The native producer must release its EGLSurface before its consumer codec is released.
        // A timeout is retryable and must propagate to the lifecycle owner.
        detachCapture()
        // Until that acknowledgement the producer can still be waiting for a codec
        // input slot. Keep its output consumer alive, even during a slow stop.
        running.set(false)
        if(endOfInputRequested.compareAndSet(false,true))
            runCatching { stage(RecorderStage.CODEC_SIGNAL_END) { codec.signalEndOfInputStream() } }
        stage(RecorderStage.DRAIN_JOIN) { drainThread?.join(5_000) }
        if (drainThread?.isAlive == true) {
            // Never release/restart a codec while its drain thread still uses it.
            // This runs on the serialized lifecycle worker, not the Android main thread.
            RecorderDiagnostics.record(context, "encoder_stop_wait input=$inputId reason=drain_still_active")
            throw IOException("Codec drain is still active; encoder ownership retained")
        }
        drainThread = null
        releaseCodec()
        if(bufferedRecording) {
            sampleWriter?.finish()
            if(sampleWriter?.awaitStopped(2_000)==false) {
                logBufferedFailure()
                throw IOException("Recording writer is still active; segment ownership retained")
            }
            logBufferedFailure()
        }
        stopCompleted = true
    }

    private fun detachCapture() {
        if (!captureOutputRequested) return
        try {
            stage(RecorderStage.CAPTURE_DETACH) { capture.stop(inputId) }
        } catch (dead: DeadObjectException) {
            // Binder death confirms that the isolated producer process and its handles are gone.
            RecorderDiagnostics.record(context,
                "encoder_detach_producer_dead input=$inputId session=$sessionId name=$codecName")
        }
        captureOutputRequested = false
    }

    private fun drain() = diagnosticStages.inLane(RecorderStageLane.DRAIN) { drainOwned() }
    private fun drainOwned() {
        if(bufferedRecording) {drainBuffered();return}
        val info = MediaCodec.BufferInfo()
        val writeInfo = MediaCodec.BufferInfo()
        var outputFormat: MediaFormat? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var target: RecordingTarget? = null
        var segmentHasSamples = false
        var segmentDamaged = false
        var segmentFirstPts = -1L
        var syncRequested = false
        val segmentUs = config.segmentMinutes * 60L * 1_000_000L
        var eos = false
        var stopDeadline = Long.MAX_VALUE
        operation = "waiting_for_output"

        fun closeSegment() {
            val active = muxer
            muxer = null
            if (active != null) {
                operation = "muxer_stop"
                val stopFailure = runCatching { stage(RecorderStage.MUXER_STOP) { active.stop() } }.exceptionOrNull()
                if (stopFailure != null) {
                    noteStorageFailure("muxer_stop", stopFailure)
                    RecorderDiagnostics.recordException(context,
                        "segment_muxer_stop_error input=$inputId outputFrames=$outputFrames " +
                            "type=${stopFailure.javaClass.name} message=${stopFailure.message}", stopFailure)
                }
                operation = "muxer_release"
                runCatching { stage(RecorderStage.MUXER_RELEASE) { active.release() } }.onFailure {
                    RecorderDiagnostics.recordException(context,
                        "segment_muxer_release_error input=$inputId type=${it.javaClass.name} message=${it.message}", it)
                }
                target?.let { segment ->
                    if (segmentHasSamples && stopFailure == null && !segmentDamaged) {
                        operation = "finish_segment"
                        stage(RecorderStage.SEGMENT_FINALIZE) { RecordingStore.finishSegment(context, segment) }
                        completedSegments++
                        RecorderDiagnostics.record(context, "segment_closed input=$inputId segments=$completedSegments outputFrames=$outputFrames outputBytes=$outputBytes")
                    } else {
                        operation = if (stopFailure == null) "abort_empty_segment" else "abort_incomplete_segment"
                        runCatching { stage(RecorderStage.SEGMENT_ABORT) { RecordingStore.abortSegment(context, segment) } }.onFailure {
                            RecorderDiagnostics.recordException(context,
                                "segment_abort_error input=$inputId type=${it.javaClass.name} message=${it.message}", it)
                        }
                        RecorderDiagnostics.record(context,
                            "segment_discarded input=$inputId reason=${if (segmentDamaged) "muxer_write_failed" else if (stopFailure == null) "empty" else "muxer_stop_failed"}")
                    }
                }
                if (stopFailure != null) throw stopFailure
            }
            target = null
            segmentUri = null
            segmentHasSamples = false
            segmentDamaged = false
            track = -1
            segmentFirstPts = -1L
            syncRequested = false
        }

        fun openSegment(format: MediaFormat) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val name = "cam${inputId}_${stamp}_s${sessionId}.mp4"
            operation = "create_segment"
            val segment = stage(RecorderStage.SEGMENT_CREATE) { RecordingStore.createSegment(context, inputId, name, storage = config.storage) }
            segmentUri = segment.uri.toString()
            RecorderDiagnostics.record(context, "segment_open input=$inputId session=$sessionId storage=${config.storage}")
            target = segment
            var createdMuxer: MediaMuxer? = null
            try {
                operation = "muxer_create_start"
                val newMuxer = stage(RecorderStage.MUXER_CREATE) { MediaMuxer(segment.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
                createdMuxer = newMuxer
                track = stage(RecorderStage.MUXER_ADD_TRACK) { newMuxer.addTrack(MediaFormat(format)) }
                stage(RecorderStage.MUXER_START) { newMuxer.start() }
                muxer = newMuxer
            } catch (t: Throwable) {
                runCatching { stage(RecorderStage.MUXER_RELEASE) { createdMuxer?.release() } }
                runCatching { stage(RecorderStage.SEGMENT_ABORT) { RecordingStore.abortSegment(context, segment) } }
                target = null
                throw t
            }
        }

        try {
            while (!eos && (running.get() || SystemClock.elapsedRealtime() < stopDeadline)) {
                if (!running.get() && stopDeadline == Long.MAX_VALUE) stopDeadline = SystemClock.elapsedRealtime() + 3_000
                operation = "codec_dequeue_output"
                when (val index = stage(RecorderStage.CODEC_DEQUEUE_OUTPUT) { codec.dequeueOutputBuffer(info, 20_000) }) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if(!stopRequested.get() && !storageFailed) {
                            operation = "codec_output_format"
                            outputFormat = stage(RecorderStage.CODEC_OUTPUT_FORMAT) { codec.outputFormat }
                        }
                    }
                    else -> if (index >= 0) {
                        operation = "codec_get_output_buffer"
                        val buffer = stage(RecorderStage.CODEC_GET_OUTPUT) { codec.getOutputBuffer(index) }
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!stopRequested.get() && !storageFailed && !isConfig && info.size > 0 && buffer != null && outputFormat != null) {
                            if (muxer == null) openSegment(outputFormat!!)
                            if (segmentFirstPts < 0) segmentFirstPts = info.presentationTimeUs
                            val elapsed = info.presentationTimeUs - segmentFirstPts
                            if (elapsed >= segmentUs - 1_000_000L && !syncRequested) {
                                runCatching { stage(RecorderStage.CODEC_REQUEST_SYNC) { codec.setParameters(Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                }) } }
                                syncRequested = true
                            }
                            if (elapsed >= segmentUs && isKey) {
                                closeSegment()
                                openSegment(outputFormat!!)
                                segmentFirstPts = info.presentationTimeUs
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeInfo.set(info.offset, info.size, info.presentationTimeUs - segmentFirstPts, info.flags)
                            val writeStart = System.nanoTime()
                            operation = "muxer_write_sample"
                            stage(RecorderStage.MUXER_WRITE_SAMPLE) { muxer?.writeSampleData(track, buffer, writeInfo) }
                            val writeNs = System.nanoTime() - writeStart
                            muxWriteTotalNs += writeNs
                            muxWriteCount++
                            if (writeNs > muxWriteMaxNs) muxWriteMaxNs = writeNs
                            segmentHasSamples = true
                            outputFrames++
                            outputBytes += info.size
                            lastOutputElapsedMs = SystemClock.elapsedRealtime()
                            lastOutputPtsUs = info.presentationTimeUs
                        }
                        operation = "codec_release_output"
                        stage(RecorderStage.CODEC_RELEASE_OUTPUT) { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
        } catch (t: Throwable) {
            val failedOperation = operation
            val failedOperationAgeMs = SystemClock.elapsedRealtime() - operationStartedElapsedMs
            failureOperation = failedOperation
            failureMessage = "${recorderCameraNames[inputId]}: ${t.message ?: t.javaClass.simpleName}"
            if (failedOperation == "muxer_write_sample") segmentDamaged = true
            noteStorageFailure(failedOperation, t)
            codecFailed = EncoderFailurePolicy.isCodecFailure(failedOperation, stopRequested.get(), storageFailed)
            val codecError = t as? MediaCodec.CodecException
            val now = SystemClock.elapsedRealtime()
            RecorderDiagnostics.recordException(context,
                "encoder_error input=$inputId operation=$failedOperation operationAgeMs=$failedOperationAgeMs " +
                    "type=${t.javaClass.name} message=${t.message} " +
                    "codecName=$codecName session=$sessionId codecLifetimeMs=${now - codecCreatedAtMs} " +
                    "codecRunningMs=${now - codecStartedAtMs} stopRequested=${stopRequested.get()} " +
                    "codecFailed=$codecFailed storageFailed=$storageFailed " +
                    "codecDiagnostic=${codecError?.diagnosticInfo} codecErrorCode=${codecError?.errorCode} " +
                    "codecRecoverable=${codecError?.isRecoverable} codecTransient=${codecError?.isTransient} " +
                    "size=${config.width}x${config.height} fps=${config.fps} bitrateMbps=${config.bitrateMbps} " +
                    "segmentMinutes=${config.segmentMinutes} storage=${config.storage} " +
                    "outputFrames=$outputFrames outputBytes=$outputBytes segments=$completedSegments " +
                    "lastOutputPtsUs=$lastOutputPtsUs lastOutputAgeMs=${now - lastOutputElapsedMs} " +
                    "metrics=${codecMetrics()}", t)
            onError(requireNotNull(failureMessage))
        } finally {
            runCatching { closeSegment() }.onFailure {
                noteStorageFailure(operation, it)
                RecorderDiagnostics.recordException(context,
                    "segment_close_error input=$inputId operation=$operation type=${it.javaClass.name} message=${it.message}", it)
                if (failureMessage == null) {
                    failureOperation = operation
                    failureMessage = "${recorderCameraNames[inputId]}: ${it.message ?: it.javaClass.simpleName}"
                }
                onError(requireNotNull(failureMessage))
            }
        }
    }

    /** Only owned compressed bytes cross to storage; every codec buffer is returned promptly. */
    private fun drainBuffered() {
        val info=MediaCodec.BufferInfo()
        var format:MediaFormat?=null
        var stopDeadline=Long.MAX_VALUE
        var eos=false
        var lastSyncDeadline=Long.MIN_VALUE
        var writerSealed=false
        operation="waiting_for_output"
        fun closingAdmission()=stopRequested.get() || storageFailed
        fun sealWriter() {
            if(!writerSealed) {sampleWriter?.finish();writerSealed=true}
        }
        try {
            while(!eos && (running.get() || SystemClock.elapsedRealtime()<stopDeadline)) {
                // Seal accepted bytes on the drain worker, not the request caller.
                // Finishing the writer is only admission/notification: its own
                // thread drains and publishes while native ownership is pending.
                if(closingAdmission())sealWriter()
                if(!running.get() && stopDeadline==Long.MAX_VALUE)stopDeadline=SystemClock.elapsedRealtime()+3_000
                if(checkpointSync.getAndSet(false) && !closingAdmission())runCatching {
                    stage(RecorderStage.CODEC_REQUEST_SYNC) {codec.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)
                    })}
                }
                operation="codec_dequeue_output"
                when(val index=stage(RecorderStage.CODEC_DEQUEUE_OUTPUT) {codec.dequeueOutputBuffer(info,20_000)}) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if(!closingAdmission()) {
                            operation="codec_output_format"
                            check(sampleWriter==null) {"Encoder format changed after recording started"}
                            format=stage(RecorderStage.CODEC_OUTPUT_FORMAT) {ownedFormat(codec.outputFormat)}
                        }
                    }
                    else -> if(index>=0) {
                        try {
                            val isConfig=info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0
                            val isKey=info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0
                            eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                            if(!closingAdmission() && !isConfig && info.size>0 && format!=null) {
                                operation="codec_get_output_buffer"
                                val buffer=stage(RecorderStage.CODEC_GET_OUTPUT) {codec.getOutputBuffer(index)}
                                if(buffer==null)continue
                                if(sampleWriter==null && isKey) {
                                    val sink=BufferedMp4Sink(context,inputId,config,sessionId,requireNotNull(format),diagnosticStages,
                                        onWritten={sample,writeNs ->
                                            muxWriteTotalNs+=writeNs;muxWriteCount++
                                            muxWriteMaxNs=maxOf(muxWriteMaxNs,writeNs)
                                            outputFrames++;outputBytes+=sample.size
                                            lastOutputElapsedMs=SystemClock.elapsedRealtime();lastOutputPtsUs=sample.presentationTimeUs
                                        },onClosed={completedSegments++})
                                    sampleSink=sink
                                    sampleWriter=EncodedSampleWriter(sink,RecorderBufferPolicy.MAX_BYTES,
                                        RecorderBufferPolicy.MAX_AGE_MS,config.fps*900+2,
                                        nowMs=SystemClock::elapsedRealtime,
                                        onFailure={error->bufferedError(error,
                                            if(error is EncodedSampleBackpressureException)"writer_queue" else sink.operation,true)},
                                        threadName="gpanel-record-writer-$inputId",
                                        storage=checkNotNull(samplePool) {"Recording session has no native sample pool"},channel=inputId)
                                }
                                sampleWriter?.let {writer ->
                                    if(closingAdmission())sealWriter()
                                    else {
                                        operation="writer_enqueue"
                                        if(writer.offer(buffer,info.offset,info.size,info.presentationTimeUs,info.flags,isKey))
                                            lastEncodedElapsedMs=SystemClock.elapsedRealtime()
                                    }
                                }
                                val deadline=sampleSink?.rotationAtPtsUs ?: Long.MAX_VALUE
                                if(!closingAdmission() && deadline!=Long.MAX_VALUE && deadline!=lastSyncDeadline && info.presentationTimeUs>=deadline-1_000_000) {
                                    lastSyncDeadline=deadline
                                    runCatching {stage(RecorderStage.CODEC_REQUEST_SYNC) {codec.setParameters(Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)
                                    })}}
                                }
                            }
                        }finally {
                            try {stage(RecorderStage.CODEC_RELEASE_OUTPUT) {codec.releaseOutputBuffer(index,false)}}
                            catch(error:Throwable) {operation="codec_release_output";throw error}
                        }
                    }
                }
            }
        }catch(error:Throwable) {bufferedError(error,operation,false)}
        finally {sampleWriter?.finish();logBufferedFailure()}
    }

    private fun bufferedError(error:Throwable,failedOperation:String,storage:Boolean) {
        if(storage)storageFailed=true
        else {
            codecFailed=EncoderFailurePolicy.isCodecFailure(failedOperation,stopRequested.get(),storageFailed)
            running.set(false)
        }
        if(bufferedFailure.compareAndSet(null,error)) {
            failureOperation=failedOperation
            failureMessage="${recorderCameraNames[inputId]}: ${error.message ?: error.javaClass.simpleName}"
            // Notify the lifecycle owner without disk I/O on queue admission.
            onError(requireNotNull(failureMessage))
        }
    }

    private fun logBufferedFailure() {
        val error=bufferedFailure.get() ?: return
        if(!bufferedFailureLogged.compareAndSet(false,true))return
        RecorderDiagnostics.recordException(context,
            "encoder_error input=$inputId session=$sessionId operation=$failureOperation codecFailed=$codecFailed storageFailed=$storageFailed writer=${writerSnapshot()}",error)
        if(storageFailed && config.storage==RecordingStorage.EXTERNAL)
            StorageDiagnostics.capture(context,"encoder input=$inputId operation=$failureOperation error=$error")
    }

    private fun ownedFormat(format:MediaFormat)=MediaFormat(format).apply {
        for(index in 0..2) {
            val key="csd-$index"
            if(format.containsKey(key))format.getByteBuffer(key)?.let {source ->
                val data=source.duplicate();val bytes=ByteArray(data.remaining());data.get(bytes)
                setByteBuffer(key,ByteBuffer.wrap(bytes))
            }
        }
    }

    private inline fun <T> stage(value:RecorderStage,action:()->T):T=RecorderStageMetrics.measure(value,action)

    private fun noteStorageFailure(operation: String, failure: Throwable) {
        if (config.storage != RecordingStorage.EXTERNAL) return
        if (operation in setOf("create_segment", "muxer_write_sample", "muxer_stop", "finish_segment") ||
            StorageRecoveryPolicy.isStorageFailure(failure.toString())) {
            storageFailed = true
            StorageDiagnostics.capture(context, "encoder input=$inputId operation=$operation error=$failure")
        }
    }

    @Suppress("DEPRECATION")
    private fun codecMetrics(): String = runCatching {
        val metrics = codec.metrics ?: return@runCatching "unavailable:null"
        metrics.keySet().sorted().take(16).joinToString(";") { key ->
            "${key.take(96)}=${metrics.get(key)?.toString()?.take(128)}"
        }.take(2_048)
    }.getOrElse { "unavailable:${it.javaClass.simpleName}:${it.message?.take(160)}" }

    private fun releaseCodec() {
        if (!codecReleased.compareAndSet(false, true)) return
        runCatching { stage(RecorderStage.CODEC_STOP) { codec.stop() } }
        runCatching { stage(RecorderStage.CODEC_RELEASE) { codec.release() } }
        if (::inputSurface.isInitialized) runCatching { stage(RecorderStage.SURFACE_RELEASE) { inputSurface.release() } }
    }
}
