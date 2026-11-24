package com.stasao.gcam

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Binder
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOutputIsolationTest {
    /** Bind the production preview controller to a fake capture service while a recorder owns its output. */
    private class CaptureContext(base: Context) : ContextWrapper(base) {
        val commands = mutableListOf<Pair<String, Int>>()
        private lateinit var connection: ServiceConnection
        val capture: IQCarCamCapture
        private val endpoint: IBinder

        init {
            val binder = Binder()
            capture = Proxy.newProxyInstance(IQCarCamCapture::class.java.classLoader,
                arrayOf(IQCarCamCapture::class.java)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "startHudV2" -> { commands += method.name to (args!![0] as Int); "preview running" }
                    "stopHud" -> { commands += method.name to (args!![0] as Int); null }
                    "hudStatus" -> { commands += method.name to (args!![0] as Int); "preview status" }
                    "hudStats" -> { commands += method.name to (args!![0] as Int); longArrayOf(1, 25) }
                    else -> throw AssertionError("Preview accessed recorder API: ${method.name}")
                }
            } as IQCarCamCapture
            binder.attachInterface(capture, "com.stasao.gcam.IQCarCamCapture")
            endpoint = binder
        }

        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            reconnect()
            return true
        }
        override fun unbindService(conn: ServiceConnection) = Unit
        fun reconnect() = connection.onServiceConnected(ComponentName(this, QCarCamCaptureService::class.java), endpoint)
    }

    @Test fun openingAndClosingFourPreviewsNeverTouchesRecorderOutputs() {
        val context = CaptureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val readers = List(4) { ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2) }
        val preview = MultiCameraPreviewController(context)
        try {
            for (input in 0..3) {
                assertEquals("preview running", preview.start(readers[input].surface, input))
                assertEquals("preview status", preview.status(input))
                assertEquals(25, preview.stats(input)[1])
            }
            preview.stop(1)
            preview.close()
            assertEquals(listOf(6, 7, 8, 9), context.commands.filter { it.first == "startHudV2" }.map { it.second })
            assertEquals(setOf(6, 7, 8, 9), context.commands.filter { it.first == "stopHud" }.map { it.second }.toSet())
        } finally {
            preview.close()
            readers.forEach { it.close() }
        }
    }

    @Test fun reconnectReattachesPreviewSlotsWithoutReplacingEncoderSurfaces() {
        val context = CaptureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val reader = ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2)
        val preview = MultiCameraPreviewController(context)
        try {
            preview.start(reader.surface, 2)
            context.commands.clear()
            context.reconnect()
            assertEquals(listOf("startHudV2" to 8), context.commands)
        } finally { preview.close(); reader.close() }
    }
    @Test fun lateOverlayCloseCannotStopTheNewActivityPreview() {
        val context = CaptureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val old = ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2)
        val current = ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2)
        val preview = MultiCameraPreviewController(context)
        try {
            PreviewCameraOutputs.start(context.capture, old.surface, 0, 15)
            preview.start(current.surface, 0)
            context.commands.clear()
            PreviewCameraOutputs.stop(context.capture, old.surface, 0)
            assertEquals(emptyList<Pair<String, Int>>(), context.commands)
            preview.stop(0)
            assertEquals(listOf("stopHud" to 6), context.commands)
        } finally { preview.close(); old.close(); current.close() }
    }

    @Test fun warningTimestampSurvivesStatusMessagesAndOlderSendersRemainCompatible() {
        val message = Intent(DashcamService.ACTION_STATE)
            .putExtra("warning", "Recording recovered")
            .putExtra("warning_at", 1_795_000_000_000L)
        val state = DashcamService.stateFrom(message)
        assertEquals("Recording recovered", state.warning)
        assertEquals(1_795_000_000_000L, state.warningAtMs)
        message.removeExtra("warning_at")
        assertEquals(0L, DashcamService.stateFrom(message).warningAtMs)
    }

}
