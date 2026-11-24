package com.stasao.gcam

import java.util.concurrent.ConcurrentMap

internal fun <T : Any> replaceEncoder(
    slots: ConcurrentMap<Int, T>,
    inputId: Int,
    failed: T,
    isActive: () -> Boolean,
    close: (T) -> Unit,
    create: () -> T,
): T? {
    if (!isActive() || slots[inputId] !== failed) return null
    close(failed)
    if (!isActive() || slots[inputId] !== failed) return null

    val replacement = create()
    if (!isActive() || slots[inputId] !== failed || !slots.replace(inputId, failed, replacement)) {
        close(replacement)
        return null
    }
    return replacement
}

internal fun <T : Any> closeEncoderSlots(
    slots: ConcurrentMap<Int, T>,
    close: (T) -> Unit,
    onFailure: (Int, Throwable) -> Unit,
): Boolean {
    val closing = slots.entries.map { it.key to it.value }
    for ((inputId, encoder) in closing) {
        try {
            close(encoder)
            slots.remove(inputId, encoder)
        } catch (failure: Throwable) {
            onFailure(inputId, failure)
        }
    }
    return slots.isEmpty()
}
