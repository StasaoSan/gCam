package com.stasao.gcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GCamBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in BOOT_ACTIONS) {
            GCamRuntimeController.startAfterBoot(context, force = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
