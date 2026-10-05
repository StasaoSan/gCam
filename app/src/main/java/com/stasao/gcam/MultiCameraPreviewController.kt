package com.stasao.gcam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

class MultiCameraPreviewController(context: Context) {
    private val appContext = context.applicationContext
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    @Volatile private var capture: IQCarCamCapture? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            capture = IQCarCamCapture.Stub.asInterface(service)
            surfaces.forEach { (id, surface) ->
                if (surface.isValid) runCatching { capture?.start(surface, id, 25) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
        }
    }

    init {
        appContext.bindService(
            Intent(appContext, QCarCamCaptureService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    fun start(surface: Surface, inputId: Int): String {
        surfaces.put(inputId, surface)?.takeIf { it !== surface }?.release()
        val remote = capture ?: return "Подключение к QCarCam…"
        return runCatching { remote.start(surface, inputId, 25) }
            .getOrElse { it.message ?: "Ошибка подключения" }
    }

    fun status(inputId: Int): String = runCatching {
        capture?.status(inputId) ?: "Подключение к QCarCam…"
    }.getOrElse { it.message ?: "Ошибка подключения" }

    fun stats(inputId: Int): LongArray = runCatching {
        capture?.stats(inputId) ?: longArrayOf()
    }.getOrDefault(longArrayOf())

    fun stop(inputId: Int) {
        runCatching { capture?.stop(inputId) }
        surfaces.remove(inputId)?.release()
    }

    fun close() {
        surfaces.keys.toList().forEach(::stop)
        runCatching { appContext.unbindService(connection) }
        capture = null
    }
}
