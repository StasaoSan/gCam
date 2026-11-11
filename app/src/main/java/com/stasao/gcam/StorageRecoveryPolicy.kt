package com.stasao.gcam

internal object StorageRecoveryPolicy {
    fun delayMs(attempts: Int): Long = when (attempts) {
        0, 1 -> 10_000L
        2 -> 20_000L
        3 -> 40_000L
        else -> 60_000L
    }

    fun isStorageFailure(reason: String): Boolean = listOf(
        "usb", "volume", "накопител", "media store", "mediastore", "storage/", "no such file",
        "eio", "i/o error", "sync failed", "writeSampleData returned an error", "enospc", "erofs"
    ).any { reason.contains(it, ignoreCase = true) }
}
