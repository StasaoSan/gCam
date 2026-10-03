package com.stasao.gcam

import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class QCarCamState(val status: String, val running: Boolean, val frames: Long,
    val dropped: Long, val firstFrameMs: Long, val fps: Double)

class QCarCamController {
    fun start(surface: Surface, inputId: Int) = NativeQCarCam.start(surface, inputId)
    fun stop() = NativeQCarCam.stop()

    fun states(): Flow<QCarCamState> = flow {
        var previousFrames = 0L
        var previousTime = System.nanoTime()
        while (true) {
            val v = NativeQCarCam.stats()
            val now = System.nanoTime()
            val frames = v.getOrElse(1) { 0L }
            val seconds = (now - previousTime).coerceAtLeast(1L) / 1_000_000_000.0
            emit(QCarCamState(NativeQCarCam.status(), v.getOrElse(0) { 0L } != 0L,
                frames, v.getOrElse(2) { 0L }, v.getOrElse(3) { 0L },
                (frames - previousFrames).coerceAtLeast(0L) / seconds))
            previousFrames = frames
            previousTime = now
            delay(500)
        }
    }.flowOn(Dispatchers.Default)
}
