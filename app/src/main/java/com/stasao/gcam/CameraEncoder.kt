package com.stasao.gcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.view.Surface
import java.io.File
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

    fun start() {
        check(running.compareAndSet(false, true))
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            val nativeStatus = NativeQCarCam.recorderStart(inputSurface, inputId)
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
        NativeQCarCam.recorderStop(inputId)
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
        var tempFile: File? = null
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
                tempFile?.let { temp ->
                    if (temp.length() > 0) temp.renameTo(File(temp.parentFile, temp.name.removeSuffix(".tmp") + ".mp4"))
                    else temp.delete()
                }
                RecordingStore.enforceLimit(context, config)
            }
            tempFile = null
            track = -1
            segmentFirstPts = -1L
            syncRequested = false
        }

        fun openSegment(format: MediaFormat) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            tempFile = File(RecordingStore.cameraDir(context, inputId), "cam${inputId}_$stamp.tmp")
            muxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                track = it.addTrack(MediaFormat(format))
                it.start()
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

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 800
        private const val FPS = 25
    }
}
