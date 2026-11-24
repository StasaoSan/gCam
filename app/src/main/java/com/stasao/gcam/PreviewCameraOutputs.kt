package com.stasao.gcam

import android.view.Surface

/** Both previews run in the UI process. A late close must only detach its own Surface. */
internal object PreviewCameraOutputs {
    private val owners = mutableMapOf<Int, Surface>()

    @Synchronized fun start(client: IQCarCamCapture, surface: Surface, input: Int, fps: Int): String {
        require(input in 0..3)
        val status = client.startHudV2(6 + input, surface, input, fps, 0f, 0f, 1f, 1f, 0f, 0, 0)
        owners[input] = surface
        return status
    }

    @Synchronized fun stop(client: IQCarCamCapture?, surface: Surface, input: Int) {
        if (owners[input] !== surface) return
        owners.remove(input)
        client?.stopHud(6 + input)
    }
}
