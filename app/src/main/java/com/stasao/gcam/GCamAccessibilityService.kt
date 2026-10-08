package com.stasao.gcam

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class GCamAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAttemptMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduleAutostart()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = scheduleAutostart()

    override fun onInterrupt() = Unit

    private fun scheduleAutostart() {
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < RETRY_INTERVAL_MS) return
        lastAttemptMs = now
        handler.postDelayed({ GCamRuntimeController.startAfterBoot(this) }, START_DELAY_MS)
    }

    private companion object {
        const val START_DELAY_MS = 2_000L
        const val RETRY_INTERVAL_MS = 30_000L
    }
}
