package com.stasao.gcam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.WeakHashMap

class MultiCameraPreviewController(context: Context) {
    companion object {
        private val active = Collections.newSetFromMap(WeakHashMap<MultiCameraPreviewController, Boolean>())

        fun closeAll() {
            val controllers = synchronized(active) { active.toList() }
            controllers.forEach { it.close() }
        }
    }
    private val appContext = context.applicationContext
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private var capture: IQCarCamCapture? = null
    private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(this@MultiCameraPreviewController) {
                if (closed) return
                capture = IQCarCamCapture.Stub.asInterface(service)
                surfaces.forEach { (id, surface) ->
                    if (surface.isValid) runCatching { capture?.start(surface, id, 25) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@MultiCameraPreviewController) { capture = null }
        }
    }

    init {
        synchronized(active) { active.add(this) }
        appContext.bindService(
            Intent(appContext, QCarCamCaptureService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    @Synchronized fun start(surface: Surface, inputId: Int): String {
        if (closed) {
            surface.release()
            return "Превью закрыто"
        }
        surfaces.put(inputId, surface)?.takeIf { it !== surface }?.release()
        val remote = capture ?: return "Подключение к QCarCam…"
        return runCatching { remote.start(surface, inputId, 25) }
            .getOrElse { it.message ?: "Ошибка подключения" }
    }

    @Synchronized fun status(inputId: Int): String = runCatching {
        capture?.status(inputId) ?: "Подключение к QCarCam…"
    }.getOrElse { it.message ?: "Ошибка подключения" }

    @Synchronized fun stats(inputId: Int): LongArray = runCatching {
        capture?.stats(inputId) ?: longArrayOf()
    }.getOrDefault(longArrayOf())

    @Synchronized fun stop(inputId: Int) {
        runCatching { capture?.stop(inputId) }
        surfaces.remove(inputId)?.release()
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        synchronized(active) { active.remove(this) }
        surfaces.keys.toList().forEach(::stop)
        runCatching { appContext.unbindService(connection) }
        capture = null
    }
}
