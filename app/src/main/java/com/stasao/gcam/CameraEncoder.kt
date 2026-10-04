package com.stasao.gcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CameraEncoder(
    private val context: Context,
    val inputId: Int,
    private val config: RecorderConfig,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private lateinit var codec: MediaCodec
    private lateinit var inputSurface: Surface
    private var drainThread: Thread? = null

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
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            val nativeStatus = capture.start(inputSurface, inputId, config.fps)
            check(nativeStatus.contains("принят", ignoreCase = true)) { nativeStatus }
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

        fun closeSegment() {
            val active = muxer
            muxer = null
            if (active != null) {
                runCatching { active.stop() }
                runCatching { active.release() }
                target?.let { segment ->
                    if (segmentHasSamples) RecordingStore.finishSegment(context, segment)
                    else RecordingStore.abortSegment(context, segment)
                }
                RecordingStore.enforceLimit(context, config)
            }
            target = null
            segmentHasSamples = false
            track = -1
            segmentFirstPts = -1L
            syncRequested = false
        }

        fun openSegment(format: MediaFormat) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val segment = RecordingStore.createSegment(context, inputId, "cam${inputId}_$stamp.mp4")
            target = segment
            try {
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
                when (val index = codec.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    else -> if (index >= 0) {
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
                            val writeInfo = MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, info.presentationTimeUs - segmentFirstPts, info.flags)
                            }
                            muxer?.writeSampleData(track, buffer, writeInfo)
                            segmentHasSamples = true
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (t: Throwable) {
            onError("${recorderCameraNames[inputId]}: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            closeSegment()
        }
    }

    private fun releaseCodec() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
    }
}
