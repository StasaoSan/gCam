package com.stasao.gcam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderFailurePolicyTest {
    @Test fun runtimeCodecFailureRequestsIsolatedEncoderRecovery() {
        assertTrue(EncoderFailurePolicy.isCodecFailure("codec_dequeue_output", false, false))
        assertTrue(EncoderFailurePolicy.isCodecFailure("codec_release_output", false, false))
    }

    @Test fun identicalExceptionDuringStorageWorkDoesNotRequestCodecRecovery() {
        assertFalse(EncoderFailurePolicy.isCodecFailure("muxer_write_sample", false, false))
        assertFalse(EncoderFailurePolicy.isCodecFailure("muxer_stop", false, false))
        assertFalse(EncoderFailurePolicy.isCodecFailure("finish_segment", false, false))
    }

    @Test fun intentionalStopAndStorageFailureTakePrecedence() {
        assertFalse(EncoderFailurePolicy.isCodecFailure("codec_dequeue_output", true, false))
        assertFalse(EncoderFailurePolicy.isCodecFailure("codec_release_output", false, true))
    }

    @Test fun startupAndUnknownOperationsDoNotBecomeRuntimeCodecFailures() {
        assertFalse(EncoderFailurePolicy.isCodecFailure("codec_configure", false, false))
        assertFalse(EncoderFailurePolicy.isCodecFailure("create_segment", false, false))
        assertFalse(EncoderFailurePolicy.isCodecFailure("waiting_for_output", false, false))
    }
}
