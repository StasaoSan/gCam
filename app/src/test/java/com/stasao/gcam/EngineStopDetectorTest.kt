package com.stasao.gcam

import org.junit.Assert.*
import org.junit.Test

class EngineStopDetectorTest {
    private fun state(now: Long, rpm: Float? = 0f, speed: Float? = 0f) = EngineStopDetector.Snapshot(
        speedKmh = speed?.let { EngineStopDetector.Sample(it, now) },
        rpm = rpm?.let { EngineStopDetector.Sample(it, now) },
    )

    @Test fun parkedStartupCannotSaveUntilRunningEngineWasObserved() {
        val detector = EngineStopDetector()
        assertFalse(detector.update(state(1000), 1000))
        assertFalse(detector.update(state(2800), 2800))
        assertFalse(detector.stopped)
    }

    @Test fun stopNeeds1800MillisecondsThenFiresOnceAndRearmsOnRisingRpm() {
        val detector = EngineStopDetector()
        assertFalse(detector.update(state(1000, rpm = 750f), 1000))
        assertFalse(detector.update(state(2000), 2000))
        assertFalse(detector.update(state(3799), 3799))
        assertTrue(detector.update(state(3800), 3800))
        assertFalse(detector.update(state(5800), 5800))
        assertFalse(detector.update(state(6000, rpm = 800f), 6000))
        assertFalse(detector.update(state(7000), 7000))
        assertTrue(detector.update(state(8800), 8800))
    }

    @Test fun movementDoesNotRearmAnAlreadySavedEngineCycle() {
        val detector = EngineStopDetector()
        detector.update(state(1000, rpm = 750f), 1000)
        detector.update(state(2000), 2000)
        assertTrue(detector.update(state(3800), 3800))
        detector.update(state(4000, speed = 2f), 4000)
        detector.update(state(5000), 5000)
        assertFalse(detector.update(state(6800), 6800))
    }

    @Test fun staleMissingAndInvalidReadingsRestartConfirmation() {
        for (missing in listOf(
            EngineStopDetector.Snapshot(), state(1000), state(3000, rpm = -1f),
            state(3000, speed = Float.NaN), state(3000, speed = -1f), state(6000),
        )) {
            val detector = EngineStopDetector()
            detector.update(state(1000, rpm = 750f), 1000)
            detector.update(state(2000), 2000)
            assertFalse(detector.update(missing, 4000))
            assertFalse(detector.update(state(4100), 4100))
            assertTrue(detector.update(state(5900), 5900))
        }
    }

    @Test fun qnxShutdownNeedsLiveTransportNewCandidateAndFreshIndependentSpeed() {
        val detector = EngineStopDetector()
        detector.update(state(1000, rpm = 750f), 1000)
        fun shutdown(now: Long, candidate: Long = 2000, alive: Long = now) =
            state(now, rpm = null).copy(telemetryAliveAtMs = alive, qnxRpmStopCandidateAtMs = candidate)
        assertFalse(detector.update(shutdown(2000), 2000))
        assertTrue(detector.update(shutdown(3800), 3800))
        for (candidate in listOf(900L, 2000L)) {
            val disconnected = EngineStopDetector()
            disconnected.update(state(1000, rpm = 750f), 1000)
            assertFalse(disconnected.update(shutdown(2000, candidate, -1), 2000))
            assertFalse(disconnected.update(shutdown(3800, candidate, -1), 3800))
        }
    }

    @Test fun uiOptionPreservesConfirmedColdStop() {
        val detector = EngineStopDetector(requireObservedRunning = false)
        assertFalse(detector.update(state(1000), 1000))
        assertTrue(detector.update(state(2800), 2800))
        assertTrue(detector.stopped)
    }

    @Test fun longGapBetweenFreshRepliesCannotCompleteOldConfirmation() {
        val detector = EngineStopDetector()
        detector.update(state(1000, rpm = 750f), 1000)
        detector.update(state(2000), 2000)
        assertFalse(detector.update(state(8000), 8000))
        assertTrue(detector.update(state(9800), 9800))
    }

    @Test fun freshQnxCandidateCanFinishItsAlreadyStartedDebounceAtOneHertz() {
        val detector = EngineStopDetector()
        detector.update(state(500, rpm = 750f), 500)
        fun shutdown(now: Long) = state(now, rpm = null).copy(
            telemetryAliveAtMs = now, qnxRpmStopCandidateAtMs = 1000,
        )
        assertFalse(detector.update(shutdown(1900), 1900))
        assertFalse(detector.update(shutdown(2900), 2900))
        assertTrue(detector.update(shutdown(3800), 3800))
        assertFalse(detector.update(shutdown(4800), 4800))
    }

    @Test fun qnxLatchCannotSurviveMissingSpeedDisconnectGapOrChangedCandidate() {
        for (interruption in listOf(
            state(2900, rpm = null, speed = null).copy(telemetryAliveAtMs = 2900, qnxRpmStopCandidateAtMs = 1000),
            state(2900, rpm = null).copy(telemetryAliveAtMs = -1, qnxRpmStopCandidateAtMs = 1000),
            state(2900, rpm = null).copy(telemetryAliveAtMs = 0, qnxRpmStopCandidateAtMs = 1000),
            state(2900, rpm = null).copy(telemetryAliveAtMs = 2900, qnxRpmStopCandidateAtMs = 500),
        )) {
            val detector = EngineStopDetector()
            detector.update(state(500, rpm = 750f), 500)
            detector.update(state(1900, rpm = null).copy(telemetryAliveAtMs = 1900, qnxRpmStopCandidateAtMs = 1000), 1900)
            assertFalse(detector.update(interruption, 2900))
            assertFalse(detector.update(state(3800, rpm = null).copy(telemetryAliveAtMs = 3800, qnxRpmStopCandidateAtMs = 1000), 3800))
        }
        val detector = EngineStopDetector()
        detector.update(state(500, rpm = 750f), 500)
        detector.update(state(1900, rpm = null).copy(telemetryAliveAtMs = 1900, qnxRpmStopCandidateAtMs = 1000), 1900)
        assertFalse(detector.update(state(5000, rpm = null).copy(telemetryAliveAtMs = 5000, qnxRpmStopCandidateAtMs = 1000), 5000))
    }

    @Test fun alreadyStaleQnxPacketCannotStartConfirmation() {
        val detector = EngineStopDetector()
        detector.update(state(500, rpm = 750f), 500)
        fun shutdown(now: Long) = state(now, rpm = null).copy(
            telemetryAliveAtMs = now, qnxRpmStopCandidateAtMs = 1000,
        )
        assertFalse(detector.update(shutdown(3800), 3800))
        assertFalse(detector.update(shutdown(5800), 5800))
    }
}
