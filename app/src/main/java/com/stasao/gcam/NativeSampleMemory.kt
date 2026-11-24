package com.stasao.gcam

import java.nio.ByteBuffer

/** Native compressed-frame storage, separate from capture/GPU native state. */
internal object NativeSampleMemory {
    val available: Boolean = try {
        System.loadLibrary("gcam_buffer")
        true
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    @JvmStatic external fun create(maxBytes: Long): Long
    @JvmStatic external fun acquire(pool: Long, channel: Int, size: Int): Long

    /** Writable exact-payload view. Its lease must outlive every read/write. */
    @JvmStatic external fun buffer(pool: Long, lease: Long): ByteBuffer?
    @JvmStatic external fun release(pool: Long, lease: Long): Boolean
    @JvmStatic external fun setBudget(pool: Long, bytes: Long)
    @JvmStatic external fun trim(pool: Long, keepFreeBytes: Long)

    /** budget, allocated capacity, leased payload bytes, live leases, free capacity. */
    @JvmStatic external fun snapshot(pool: Long): LongArray

    /** Returns false while a writer or queue still owns any lease. */
    @JvmStatic external fun destroy(pool: Long): Boolean
}
