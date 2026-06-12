package com.stasao.gcam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class CameraOpenResult {
    data class Success(val method: String) : CameraOpenResult()
    data class Failure(val method: String, val reason: String) : CameraOpenResult()
}

@Suppress("DEPRECATION")
class CameraController(private val context: Context) {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var camera1: android.hardware.Camera? = null
    private var evsCamera: Any? = null

    @SuppressLint("MissingPermission")
    suspend fun tryCamera2(holder: SurfaceHolder, width: Int, height: Int): CameraOpenResult {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return CameraOpenResult.Failure("Camera2", "CAMERA_SERVICE = null")

            val ids = try {
                manager.cameraIdList
            } catch (e: Exception) {
                return CameraOpenResult.Failure("Camera2", "cameraIdList: ${e.message}")
            }

            if (ids.isEmpty()) return CameraOpenResult.Failure("Camera2", "cameraIdList пуст")

            val ht = HandlerThread("Cam2Thread").also { it.start() }
            handlerThread = ht
            val h = Handler(ht.looper)
            handler = h

            val device = suspendCancellableCoroutine<CameraDevice?> { cont ->
                cont.invokeOnCancellation { ht.quitSafely() }
                try {
                    manager.openCamera(ids[0], object : CameraDevice.StateCallback() {
                        override fun onOpened(cam: CameraDevice) {
                            if (cont.isActive) cont.resume(cam)
                        }
                        override fun onDisconnected(cam: CameraDevice) {
                            cam.close(); if (cont.isActive) cont.resume(null)
                        }
                        override fun onError(cam: CameraDevice, error: Int) {
                            cam.close(); if (cont.isActive) cont.resume(null)
                        }
                    }, h)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            } ?: return CameraOpenResult.Failure("Camera2", "openCamera вернул null/error")

            cameraDevice = device
            val surface = holder.surface

            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    device.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    val req = device
                                        .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        .apply { addTarget(surface) }
                                        .build()
                                    session.setRepeatingRequest(req, null, h)
                                    if (cont.isActive) cont.resume(true)
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resume(false)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                if (cont.isActive) cont.resume(false)
                            }
                        }, h)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }

            if (ok) CameraOpenResult.Success("Camera2 API")
            else CameraOpenResult.Failure("Camera2", "createCaptureSession onConfigureFailed")
        } catch (e: Exception) {
            CameraOpenResult.Failure("Camera2", e.message ?: "unknown error")
        }
    }

    fun tryCamera1(holder: SurfaceHolder, width: Int, height: Int): CameraOpenResult {
        return try {
            val count = android.hardware.Camera.getNumberOfCameras()
            if (count == 0) return CameraOpenResult.Failure("Camera1", "getNumberOfCameras() = 0")
            val cam = android.hardware.Camera.open(0)
            val params = cam.parameters
            val best = params.supportedPreviewSizes
                ?.minByOrNull { kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }
            if (best != null) {
                params.setPreviewSize(best.width, best.height)
                cam.parameters = params
            }
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            camera1 = cam
            CameraOpenResult.Success("Camera1 API (legacy)")
        } catch (e: Exception) {
            CameraOpenResult.Failure("Camera1", e.message ?: "unknown error")
        }
    }

    fun tryEvsCamera(holder: SurfaceHolder): CameraOpenResult {
        return try {
            val evsCls = Class.forName("com.ecarx.xui.adaptapi.evs.EVSImp")
            val createM = evsCls.methods.first { it.name == "create" }
            // create() is static, returns EVSImp itself
            val evsimp = createM.invoke(null, context)
                ?: return CameraOpenResult.Failure("EVSImp", "create(context) вернул null")

            val cam = evsimp.javaClass.getMethod("getEvsCamera").invoke(evsimp)
                ?: return CameraOpenResult.Failure("EVSImp", "getEvsCamera() вернул null")

            val openM = cam.javaClass.getMethod("open", Int::class.java)
            var openedId = -1
            for (id in 0..4) {
                if (openM.invoke(cam, id) as? Boolean == true) { openedId = id; break }
            }
            if (openedId < 0) return CameraOpenResult.Failure("EVSImp", "open(0..4) все вернули false")

            cam.javaClass.getMethod("setPreviewDisplay", SurfaceHolder::class.java).invoke(cam, holder)

            val started = cam.javaClass.getMethod("startPreview").invoke(cam) as? Boolean ?: false
            if (!started) return CameraOpenResult.Failure("EVSImp", "startPreview() false (cam#$openedId)")

            evsCamera = cam
            CameraOpenResult.Success("ECarX EVSImp cam#$openedId")
        } catch (t: Throwable) {
            CameraOpenResult.Failure("EVSImp", "${t.javaClass.simpleName}: ${t.message?.take(80)}")
        }
    }

    fun release() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
        handler = null
        try { camera1?.stopPreview(); camera1?.release() } catch (_: Exception) {}
        camera1 = null
        try { evsCamera?.javaClass?.getMethod("stopPreview")?.invoke(evsCamera) } catch (_: Exception) {}
        try { evsCamera?.javaClass?.getMethod("release")?.invoke(evsCamera) } catch (_: Exception) {}
        evsCamera = null
    }
}
