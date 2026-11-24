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

    fun load(context: Context): Int {
        RecorderHost.language?.let { return it(context) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val locale = context.getSystemService(android.app.LocaleManager::class.java)
                .applicationLocales
            return if (locale.isEmpty) SYSTEM else if (locale[0].language == "ru") RUSSIAN else ENGLISH
        }
        return processLanguage ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LANGUAGE, SYSTEM).coerceIn(SYSTEM, ENGLISH)
    }

    /** Move an existing in-app choice to Android's per-app language setting once. */
    fun migrate(context: Context) {
        if (RecorderHost.embedded(context) || android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("platform_locale_migrated", false)) return
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val previous = prefs.getInt(KEY_LANGUAGE, SYSTEM)
        if (manager.applicationLocales.isEmpty && previous in RUSSIAN..ENGLISH) {
            manager.applicationLocales = android.os.LocaleList.forLanguageTags(if (previous == RUSSIAN) "ru" else "en")
        }
        prefs.edit().putBoolean("platform_locale_migrated", true).apply()
    }

    fun save(context: Context, language: Int) {
        require(language in SYSTEM..ENGLISH)
        processLanguage = language
        if (!RecorderHost.embedded(context) && android.os.Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                android.os.LocaleList.forLanguageTags(when (language) {
                    RUSSIAN -> "ru"
                    ENGLISH -> "en"
                    else -> ""
                })
        }
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

    private data class ResourceCache(
        val base: android.content.res.Resources,
        val configuration: android.content.res.Configuration,
        val language: Int,
        val localized: android.content.res.Resources
    )
    @Volatile private var resourceCache: ResourceCache? = null

    fun string(context: Context, @androidx.annotation.StringRes id: Int, vararg args: Any?): String =
        inLanguage(context, load(context), id, *args)

    internal fun inLanguage(context: Context, language: Int,
                            @androidx.annotation.StringRes id: Int, vararg args: Any?): String {
        val base = context.resources
        val resources = if (language == SYSTEM) base else {
            val cached = resourceCache
            if (cached != null && cached.base === base && cached.language == language &&
                cached.configuration == base.configuration) cached.localized
            else {
                val configuration = android.content.res.Configuration(base.configuration)
                configuration.setLocale(Locale.forLanguageTag(if (language == RUSSIAN) "ru" else "en"))
                val localized = context.createConfigurationContext(configuration).resources
                resourceCache = ResourceCache(base, android.content.res.Configuration(base.configuration), language, localized)
                localized
            }
        }
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }

    fun display(context: Context, text: String): String =
        if (isEnglish(context)) RecorderStatusMessages.translate(context, text) else text
}
