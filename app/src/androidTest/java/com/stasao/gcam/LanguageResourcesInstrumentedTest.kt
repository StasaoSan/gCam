package com.stasao.gcam

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageResourcesInstrumentedTest {
    @Test fun androidResolvesBothLocalesAndFormatsValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Камеры и запись", GCamLanguage.inLanguage(context, GCamLanguage.RUSSIAN, R.string.gcam_cameras_recording))
        assertEquals("Cameras & recording", GCamLanguage.inLanguage(context, GCamLanguage.ENGLISH, R.string.gcam_cameras_recording))
        assertEquals("✓ SELECTED · Drive 100% · disk1", GCamLanguage.inLanguage(context,
            GCamLanguage.ENGLISH, R.string.gcam_drive_selected, "Drive 100%", "disk1"))
        assertEquals("Тест USB: запись · 50% · 12/60 с", GCamLanguage.inLanguage(context,
            GCamLanguage.RUSSIAN, R.string.gcam_usb_test_progress, "запись", 50, 12))
        for ((tag, expected) in listOf("ru" to "Камеры и запись", "en" to "Cameras & recording", "de" to "Cameras & recording")) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            assertEquals(expected, context.createConfigurationContext(config).getString(R.string.gcam_cameras_recording))
        }
    }
}
