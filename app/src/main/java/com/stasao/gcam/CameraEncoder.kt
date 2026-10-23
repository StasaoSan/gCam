package com.stasao.gcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CameraEncoder(
    private val context: Context,
    val inputId: Int,
    private val config: RecorderConfig,
    private val sessionId: Long,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private lateinit var codec: MediaCodec
    private lateinit var inputSurface: Surface
    private var drainThread: Thread? = null
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

    private lateinit var capture: IQCarCamCapture

    fun start(capture: IQCarCamCapture) {
        check(running.compareAndSet(false, true))
        this.capture = capture
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            RecorderDiagnostics.record(context,
                "encoder_config input=$inputId name=${codec.name} hardware=${codec.codecInfo.isHardwareAccelerated} " +
                    "size=${config.width}x${config.height} fps=${config.fps} bitrateMbps=${config.bitrateMbps}")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            val nativeStatus = capture.start(inputSurface, inputId, config.fps)
            check(nativeStatus.contains("принят", ignoreCase = true)) { nativeStatus }
            lastOutputElapsedMs = SystemClock.elapsedRealtime()
            drainThread = Thread(::drain, "gcam-encoder-$inputId").also { it.start() }
        } catch (t: Throwable) {
            running.set(false)
            releaseCodec()
            throw t
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        if (::capture.isInitialized) runCatching { capture.stop(inputId) }
        runCatching { codec.signalEndOfInputStream() }
        drainThread?.join(5_000)
        drainThread = null
        releaseCodec()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val writeInfo = MediaCodec.BufferInfo()
        var outputFormat: MediaFormat? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var target: RecordingTarget? = null
        var segmentHasSamples = false
        var segmentFirstPts = -1L
        var syncRequested = false
        val segmentUs = config.segmentMinutes * 60L * 1_000_000L
        var eos = false
        var stopDeadline = Long.MAX_VALUE
        var operation = "waiting_for_output"

        fun closeSegment() {
            val active = muxer
            muxer = null
            if (active != null) {
                operation = "muxer_stop"
                runCatching { active.stop() }.onFailure {
                    RecorderDiagnostics.recordException(context,
                        "segment_muxer_stop_error input=$inputId outputFrames=$outputFrames type=${it.javaClass.name} message=${it.message}", it)
                }
                operation = "muxer_release"
                runCatching { active.release() }.onFailure {
                    RecorderDiagnostics.recordException(context,
                        "segment_muxer_release_error input=$inputId type=${it.javaClass.name} message=${it.message}", it)
                }
                target?.let { segment ->
                    if (segmentHasSamples) {
                        operation = "finish_segment"
                        RecordingStore.finishSegment(context, segment)
                        completedSegments++
                        RecorderDiagnostics.record(context, "segment_closed input=$inputId segments=$completedSegments outputFrames=$outputFrames outputBytes=$outputBytes")
                    } else {
                        operation = "abort_empty_segment"
                        RecordingStore.abortSegment(context, segment)
                        RecorderDiagnostics.record(context, "segment_empty input=$inputId")
                    }
                }
            }
            target = null
            segmentHasSamples = false
            track = -1
            segmentFirstPts = -1L
            syncRequested = false
        }

        fun openSegment(format: MediaFormat) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val name = "cam${inputId}_${stamp}_s${sessionId}.mp4"
            operation = "create_segment"
            val segment = RecordingStore.createSegment(context, inputId, name, storage = config.storage)
            RecorderDiagnostics.record(context, "segment_open input=$inputId name=$name")
            target = segment
            try {
                operation = "muxer_create_start"
                muxer = MediaMuxer(segment.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                    track = it.addTrack(MediaFormat(format))
                    it.start()
                }
            } catch (t: Throwable) {
                RecordingStore.abortSegment(context, segment)
                target = null
                throw t
            }
        }

        try {
            while (!eos && (running.get() || System.currentTimeMillis() < stopDeadline)) {
                if (!running.get() && stopDeadline == Long.MAX_VALUE) stopDeadline = System.currentTimeMillis() + 3_000
                operation = "codec_dequeue_output"
                when (val index = codec.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        operation = "codec_output_format"
                        outputFormat = codec.outputFormat
                    }
                    else -> if (index >= 0) {
                        operation = "codec_get_output_buffer"
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!isConfig && info.size > 0 && buffer != null && outputFormat != null) {
                            if (muxer == null) openSegment(outputFormat!!)
                            if (segmentFirstPts < 0) segmentFirstPts = info.presentationTimeUs
                            val elapsed = info.presentationTimeUs - segmentFirstPts
                            if (elapsed >= segmentUs - 1_000_000L && !syncRequested) {
                                runCatching { codec.setParameters(Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                }) }
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
                            muxer?.writeSampleData(track, buffer, writeInfo)
                            val writeNs = System.nanoTime() - writeStart
                            muxWriteTotalNs += writeNs
                            muxWriteCount++
                            if (writeNs > muxWriteMaxNs) muxWriteMaxNs = writeNs
                            segmentHasSamples = true
                            outputFrames++
                            outputBytes += info.size
                            lastOutputElapsedMs = SystemClock.elapsedRealtime()
                        }
                        operation = "codec_release_output"
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (t: Throwable) {
            val codecError = t as? MediaCodec.CodecException
            RecorderDiagnostics.recordException(context,
                "encoder_error input=$inputId operation=$operation type=${t.javaClass.name} message=${t.message} " +
                    "codecDiagnostic=${codecError?.diagnosticInfo} codecErrorCode=${codecError?.errorCode} " +
                    "codecRecoverable=${codecError?.isRecoverable} codecTransient=${codecError?.isTransient} " +
                    "outputFrames=$outputFrames segments=$completedSegments lastOutputAgeMs=" +
                    "${SystemClock.elapsedRealtime() - lastOutputElapsedMs}", t)
            onError("${recorderCameraNames[inputId]}: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { closeSegment() }.onFailure {
                RecorderDiagnostics.recordException(context,
                    "segment_close_error input=$inputId operation=$operation type=${it.javaClass.name} message=${it.message}", it)
                onError("${recorderCameraNames[inputId]}: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun releaseCodec() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
    }
}
