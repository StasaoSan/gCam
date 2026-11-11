package com.stasao.gcam

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Bounded local commands must neither fill memory nor hold up recovery. */
internal object DiagnosticCommand {
    data class Result(val exitCode: Int?, val timedOut: Boolean, val output: String)

    fun run(command: List<String>, timeoutMs: Long = 4_000, maxChars: Int = 64 * 1024): Result {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val tail = StringBuilder()
        val reader = thread(name = "gcam-diag-reader", isDaemon = true) {
            runCatching {
                process.inputStream.reader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(tail) {
                            tail.append(buffer, 0, count)
                            if (tail.length > maxChars) tail.delete(0, tail.length - maxChars)
                        }
                    }
                }
            }
        }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(500)
            return Result(if (finished) process.exitValue() else null, !finished,
                synchronized(tail) { tail.toString() })
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }
}
