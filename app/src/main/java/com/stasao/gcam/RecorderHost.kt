package com.stasao.gcam

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider

/** Integration points shared by the recorder's standalone and gPanel builds. */
object RecorderHost {
    private const val PANEL = "com.stasao.gpanel"
    private const val CAPTURE_SERVICE = "com.stasao.gcam.QCarCamCaptureService"
    internal val telemetryUri: Uri = Uri.parse("content://$PANEL.recorder.telemetry")
    internal val legacyPackages = listOf("com.stasao.gcam.dev", "com.stasao.gcam")
    internal var navigation: ((Activity) -> View)? = null
        private set
    internal var language: ((Context) -> Int)? = null
        private set
    @Volatile private var contextWrapper: (Context)->Context = {it}


    fun initialize(
        navigation: (Activity) -> View,
        language: (Context) -> Int,
        wrapContext: (Context)->Context = {it},
    ) {
        this.navigation = navigation
        this.language = language
        contextWrapper=wrapContext
    }

    fun wrapContext(context:Context):Context=contextWrapper(context)

    fun embedded(context: Context): Boolean = context.packageName == PANEL

    fun capturePackage(context: Context): String? = if (embedded(context)) {
        legacyCapturePackage(context) ?: context.packageName
    } else {
        context.packageName.takeIf { captureEnabled(context, it) }
    }

    fun embeddedOwnsCapture(context: Context): Boolean =
        embedded(context) && legacyCapturePackage(context) == null

    internal fun captureAvailable(context: Context): Boolean =
        capturePackage(context) == context.packageName

    internal fun canRestoreLegacyCapture(context: Context): Boolean =
        !embedded(context) && !captureAvailable(context) && !captureEnabled(context, PANEL)

    internal fun restoreLegacyCapture(context: Context) {
        check(canRestoreLegacyCapture(context))
        val component = ComponentName(context.packageName, CAPTURE_SERVICE)
        context.packageManager.setComponentEnabledSetting(
            component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
        )
    }

    internal fun legacyCapturePackage(context: Context): String? =
        legacyPackages.firstOrNull { captureEnabled(context, it) }

    internal fun captureEnabled(context: Context, packageName: String): Boolean {
        val manager = context.packageManager
        val component = ComponentName(packageName, CAPTURE_SERVICE)
        return runCatching {
            val info = manager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            val enabled = manager.getComponentEnabledSetting(component)
            info.applicationInfo.enabled && (enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled))
        }.getOrDefault(false)
    }

    internal fun fileAuthority(context: Context): String =
        if (embedded(context)) "${context.packageName}.recorder.files" else "${context.packageName}.fileprovider"
}

class RecorderFileProvider : FileProvider()
