package com.stasao.gcam

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import org.junit.Assert.*
import org.junit.Test

class EncoderReplacementTest {
    private class Resource {
        var closed = false
        var closeCalls = 0
        fun close() { closeCalls++; check(!closed); closed = true }
    }

    @Test fun replacementClosesFailedEncoderBeforeCreatingAndLeavesHealthyEncoderRunning() {
        val failed = Resource()
        val healthy = Resource()
        val replacement = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed, 1 to healthy))

        val result = replaceEncoder(slots, 0, failed, { true }, {
            assertSame(failed, slots[0])
            it.close()
            assertSame(failed, slots[0])
        }) {
            assertTrue(failed.closed)
            assertSame(failed, slots[0])
            assertFalse(healthy.closed)
            replacement
        }

        assertSame(replacement, result)
        assertSame(replacement, slots[0])
        assertSame(healthy, slots[1])
        assertFalse(healthy.closed)
        assertFalse(replacement.closed)
    }

    @Test fun inactiveSessionDoesNotCloseOrCreateAnyEncoder() {
        val failed = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed))
        val result = replaceEncoder(slots, 0, failed, { false }, { it.close() }) {
            error("Cancelled session must not allocate an encoder")
        }
        assertNull(result)
        assertSame(failed, slots[0])
        assertFalse(failed.closed)
    }

    @Test fun staleFailedEncoderDoesNotCloseTheCurrentOwner() {
        val failed = Resource()
        val current = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to current))
        val result = replaceEncoder(slots, 0, failed, { true }, { it.close() }) {
            error("Stale restart must not allocate an encoder")
        }
        assertNull(result)
        assertSame(current, slots[0])
        assertFalse(current.closed)
        assertFalse(failed.closed)
    }

    @Test fun cancellationDuringClosePreventsCreatingAnEncoder() {
        val failed = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed))
        var active = true
        val result = replaceEncoder(slots, 0, failed, { active }, {
            it.close()
            active = false
        }) { error("Cancellation during finalization must prevent allocation") }
        assertNull(result)
        assertTrue(failed.closed)
        assertSame(failed, slots[0])
    }

    @Test fun ownershipChangeDuringClosePreventsCreatingAnEncoder() {
        val failed = Resource()
        val current = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed))
        val result = replaceEncoder(slots, 0, failed, { true }, {
            it.close()
            slots[0] = current
        }) { error("A newer owner must prevent allocation") }
        assertNull(result)
        assertTrue(failed.closed)
        assertSame(current, slots[0])
        assertFalse(current.closed)
    }

    @Test fun cancellationDuringCreationClosesNewEncoderWithoutPublishingIt() {
        val failed = Resource()
        val replacement = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed))
        var active = true
        val result = replaceEncoder(slots, 0, failed, { active }, { it.close() }) {
            active = false
            replacement
        }
        assertNull(result)
        assertTrue(failed.closed)
        assertTrue(replacement.closed)
        assertSame(failed, slots[0])
    }

    @Test fun ownershipChangeDuringCreationClosesNewEncoderWithoutOverwritingCurrentOwner() {
        val failed = Resource()
        val replacement = Resource()
        val current = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed))
        val result = replaceEncoder(slots, 0, failed, { true }, { it.close() }) {
            slots[0] = current
            replacement
        }
        assertNull(result)
        assertTrue(failed.closed)
        assertTrue(replacement.closed)
        assertSame(current, slots[0])
        assertFalse(current.closed)
    }

    @Test fun closeFailurePropagatesWithoutCreatingAnEncoder() {
        val failed = Resource()
        val healthy = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed, 1 to healthy))
        val failure = IllegalStateException("Cannot finalize failed encoder")
        val thrown = assertThrows(IllegalStateException::class.java) {
            replaceEncoder(slots, 0, failed, { true }, { throw failure }) {
                error("Failed finalization must prevent allocation")
            }
        }
        assertSame(failure, thrown)
        assertSame(failed, slots[0])
        assertSame(healthy, slots[1])
        assertFalse(healthy.closed)
    }

    @Test fun creationFailurePropagatesWithoutRemovingFailedSlotOrClosingHealthyEncoder() {
        val failed = Resource()
        val healthy = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed, 1 to healthy))
        val failure = IllegalStateException("Cannot create encoder")
        val thrown = assertThrows(IllegalStateException::class.java) {
            replaceEncoder(slots, 0, failed, { true }, { it.close() }) { throw failure }
        }
        assertSame(failure, thrown)
        assertTrue(failed.closed)
        assertSame(failed, slots[0])
        assertSame(healthy, slots[1])
        assertFalse(healthy.closed)
    }

    @Test fun failedCloseRetainsOnlyFailedSlotAndReportsItsError() {
        val failed = Resource()
        val healthy = Resource()
        val failure = IllegalStateException("Native detach failed")
        val slots = ConcurrentHashMap(mapOf(0 to failed, 1 to healthy))
        val reported = mutableMapOf<Int, Throwable>()

        val finished = closeEncoderSlots(slots, {
            if (it === failed) throw failure
            it.close()
        }) { inputId, error -> reported[inputId] = error }

        assertFalse(finished)
        assertEquals(setOf(0), slots.keys)
        assertSame(failed, slots[0])
        assertFalse(failed.closed)
        assertTrue(healthy.closed)
        assertEquals(setOf(0), reported.keys)
        assertSame(failure, reported[0])
    }

    @Test fun retryClosesOnlyRetainedFailedEncoderWithoutStoppingHealthyEncoderAgain() {
        val failed = Resource()
        val healthy = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to failed, 1 to healthy))
        closeEncoderSlots(slots, {
            if (it === failed) throw IllegalStateException("Native detach failed")
            it.close()
        }) { _, _ -> }

        val finished = closeEncoderSlots(slots, { it.close() }) { _, error -> throw error }

        assertTrue(finished)
        assertTrue(slots.isEmpty())
        assertTrue(failed.closed)
        assertEquals(1, failed.closeCalls)
        assertEquals(1, healthy.closeCalls)
    }

    @Test fun newOwnerInstalledDuringCloseIsNotRemovedOrClosed() {
        val old = Resource()
        val current = Resource()
        val slots = ConcurrentHashMap(mapOf(0 to old))
        val finished = closeEncoderSlots(slots, {
            it.close()
            slots[0] = current
        }) { _, error -> throw error }
        assertFalse(finished)
        assertSame(current, slots[0])
        assertTrue(old.closed)
        assertFalse(current.closed)
        assertEquals(0, current.closeCalls)
    }

    @Test fun detachFailureDoesNotPreventClosingRemainingEncoders() {
        val failed = Resource()
        val healthy = Resource()
        // Sorted slots make the failing detach occur before the healthy close.
        val slots = ConcurrentSkipListMap(mapOf(0 to failed, 1 to healthy))
        val events = mutableListOf<String>()

        val finished = closeEncoderSlots(slots, {
            if (it === failed) {
                events += "failed"
                throw IllegalStateException("Native detach failed")
            }
            events += "healthy"
            it.close()
        }) { _, _ -> events += "reported" }

        assertFalse(finished)
        assertEquals(listOf("failed", "reported", "healthy"), events)
        assertSame(failed, slots[0])
        assertNull(slots[1])
        assertTrue(healthy.closed)
    }
}
