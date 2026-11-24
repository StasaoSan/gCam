package com.stasao.gcam

/** Only runtime codec calls can trigger isolated encoder replacement. */
internal object EncoderFailurePolicy {
    fun isCodecFailure(operation: String, stopRequested: Boolean, storageFailed: Boolean): Boolean =
        !stopRequested && !storageFailed && operation in setOf(
            "codec_dequeue_output", "codec_output_format", "codec_get_output_buffer", "codec_release_output"
        )
}
