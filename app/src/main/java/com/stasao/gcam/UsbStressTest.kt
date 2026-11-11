package com.stasao.gcam

import java.io.InputStream
import java.io.OutputStream
import java.util.Random

internal class UsbTestFailure(message: String) : IllegalStateException(message)

/** Bounded memory, four interleaved files; timing includes flush/sync and verification. */
internal object UsbStressTest {
    const val TOTAL_BYTES = 200_000_000L
    const val DEADLINE_MS = 60_000L
    interface TestFile : AutoCloseable {
        val output: OutputStream
        fun sync()
        fun openInput(): InputStream
    }
    data class Result(val writeBytesPerSecond: Double, val readBytesPerSecond: Double,
                      val maxOperationMs: Long, val elapsedMs: Long) {
        fun hasHeadroom(cameras: Int, bitrateMbps: Int): Boolean =
            writeBytesPerSecond >= cameras * bitrateMbps * 1_000_000.0 / 8 * 2
    }

    fun run(files: List<TestFile>, bytes: Long = TOTAL_BYTES,
            nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
            checkConnected: () -> Unit = {}, progress: (String, Int) -> Unit = { _, _ -> }): Result {
        require(files.size == 4 && bytes > 0)
        val started = nowMs()
        val seed = System.nanoTime()
        var maxOperation = 0L
        fun check() {
            checkConnected()
            if (nowMs() - started >= DEADLINE_MS) throw UsbTestFailure("USB test timeout (60 s)")
        }
        fun operation(block: () -> Unit) {
            check()
            val start = nowMs()
            block()
            maxOperation = maxOf(maxOperation, nowMs() - start)
            check()
        }
        val sizes = LongArray(4) { bytes / 4 + if (it < bytes % 4) 1 else 0 }
        val buffer = ByteArray(256 * 1024)
        val actual = ByteArray(buffer.size)
        val offsets = LongArray(4)
        val writers = Array(4) { Random(seed + it) }
        var done = 0L
        progress("write", 0)
        while (done < bytes) for (i in files.indices) {
            if (offsets[i] >= sizes[i]) continue
            check()
            val count = minOf(buffer.size.toLong(), sizes[i] - offsets[i]).toInt()
            writers[i].nextBytes(buffer)
            operation { files[i].output.write(buffer, 0, count) }
            offsets[i] += count
            done += count
            if (offsets[i] % (4 * 1024 * 1024L) == 0L) operation { files[i].sync() }
            progress("write", (done * 50 / bytes).toInt())
        }
        files.forEach { file -> operation { file.sync() } }
        val writtenAt = nowMs()
        done = 0
        files.forEachIndexed { i, file ->
            val reader = Random(seed + i)
            file.openInput().use { input ->
                var offset = 0L
                while (offset < sizes[i]) {
                    check()
                    val count = minOf(buffer.size.toLong(), sizes[i] - offset).toInt()
                    reader.nextBytes(buffer)
                    operation {
                        var read = 0
                        while (read < count) {
                            val n = input.read(actual, read, count - read)
                            if (n <= 0) throw UsbTestFailure("USB test incomplete read (file $i)")
                            read += n
                            check()
                        }
                    }
                    for (j in 0 until count) if (actual[j] != buffer[j])
                        throw UsbTestFailure("USB test data mismatch (file $i, offset ${offset + j})")
                    offset += count
                    done += count
                    progress("read", 50 + (done * 50 / bytes).toInt())
                }
            }
        }
        check()
        val ended = nowMs()
        return Result(bytes * 1000.0 / (writtenAt - started).coerceAtLeast(1),
            bytes * 1000.0 / (ended - writtenAt).coerceAtLeast(1), maxOperation, ended - started)
    }
}
