package com.stasao.gcam

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class EngineCheckpointReaderTest {
    @Test fun restartingRejectsReplyFromThePreviousGeneration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val reads = AtomicInteger()
        val replies = AtomicInteger()
        val reader = EngineCheckpointReader({
            if (reads.incrementAndGet() == 1) { entered.countDown(); release.await() }
            EngineStopDetector.Snapshot()
        }, { _, _ -> replies.incrementAndGet(); completed.countDown() }, EngineCheckpointReader.Actor())
        reader.start(); reader.request()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        reader.stop(); reader.start(); reader.request()
        release.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, reads.get())
        assertEquals(1, replies.get())
        reader.stop()
    }

    @Test fun stoppingNeverWaitsForBlockedIoAndDropsItsLateReply() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val replies = AtomicInteger()
        val actor = EngineCheckpointReader.Actor()
        val reader = EngineCheckpointReader({ entered.countDown(); release.await(); null }, { _, _ -> replies.incrementAndGet() }, actor)
        reader.start()
        reader.request()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        reader.stop()
        val next = EngineCheckpointReader({ null }, { _, _ -> drained.countDown() }, actor)
        next.start()
        next.request()
        release.countDown()
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertEquals(0, replies.get())
        next.stop()
    }

    @Test fun blockedWorkerKeepsOnlyLatestPendingRequestAndRejectsStoppedRequests() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val reads = AtomicInteger()
        val actor = EngineCheckpointReader.Actor()
        val blocked = EngineCheckpointReader({ entered.countDown(); release.await(); null }, { _, _ -> }, actor)
        blocked.start(); blocked.request()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val cancelled = EngineCheckpointReader({ reads.incrementAndGet(); null }, { _, _ -> }, actor)
        cancelled.start()
        repeat(1000) { cancelled.request() }
        cancelled.stop()
        val latest = EngineCheckpointReader({ reads.incrementAndGet(); null }, { _, _ -> drained.countDown() }, actor)
        latest.start(); latest.request()
        release.countDown()
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertEquals(1, reads.get())
        blocked.stop(); latest.stop()
    }
}
