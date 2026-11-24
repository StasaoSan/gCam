package com.stasao.gcam

/** Explains the first observable break between gCam and another display app. */
internal object ImagePathAdvice {
    data class State(
        val installed: Boolean,
        val access: Boolean,
        val reportFresh: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
        val video: Boolean,
        val error: String?,
    )

    fun explain(context: android.content.Context, app: String, state: State): String =
        GCamLanguage.string(context, messageId(state), app,
            GCamLanguage.display(context, state.error.orEmpty()))

    internal fun messageId(state: State): Int = when {
        !state.installed -> R.string.gcam_path_app_missing
        !state.access -> R.string.gcam_path_access_missing
        !state.reportFresh -> R.string.gcam_path_status_missing
        !state.enabled -> R.string.gcam_path_disabled
        !state.error.isNullOrBlank() -> R.string.gcam_path_error
        !state.connected -> R.string.gcam_path_disconnected
        !state.video -> R.string.gcam_path_frames_missing
        else -> R.string.gcam_path_ready
    }
}
