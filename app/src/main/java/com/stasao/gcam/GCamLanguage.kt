package com.stasao.gcam

import android.content.Context
import java.util.Locale

/** UI language is independent of the head unit's system language. */
object GCamLanguage {
    const val SYSTEM = 0
    const val RUSSIAN = 1
    const val ENGLISH = 2

    private const val PREFS = "gcam_language"
    private const val KEY_LANGUAGE = "language"
    @Volatile private var processLanguage: Int? = null

    fun load(context: Context): Int = processLanguage ?:
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LANGUAGE, SYSTEM).coerceIn(SYSTEM, ENGLISH)

    fun save(context: Context, language: Int) {
        require(language in SYSTEM..ENGLISH)
        processLanguage = language
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LANGUAGE, language).apply()
    }

    /** Keep an already-running recorder process in sync with the UI process. */
    fun useInProcess(language: Int) {
        if (language in SYSTEM..ENGLISH) processLanguage = language
    }

    fun isEnglish(context: Context, language: Int = load(context)): Boolean = when (language) {
        RUSSIAN -> false
        ENGLISH -> true
        else -> (context.resources.configuration.locales.get(0)?.language
            ?: Locale.getDefault().language) != "ru"
    }

    fun text(context: Context, russian: String, english: String): String =
        if (isEnglish(context)) english else russian

    fun display(context: Context, text: String): String =
        if (isEnglish(context)) EnglishUi.translate(text) else text
}
