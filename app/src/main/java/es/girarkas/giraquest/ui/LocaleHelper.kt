package es.girarkas.giraquest.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import es.girarkas.giraquest.R
import es.girarkas.giraquest.data.SettingsStore
import java.util.Locale

/**
 * Selector de idioma multi-región para GiraQuest.
 * Funciona de forma determinista mediante attachBaseContext() + configuración
 * en SharedPreferences, sin depender de servicios de sistema opcionales en Horizon OS.
 */
object LocaleHelper {

    data class LanguageOption(val tag: String, val nativeName: String)

    val LANGUAGES: List<LanguageOption> = listOf(
        LanguageOption("", ""),
        LanguageOption("es", "Español"),
        LanguageOption("en", "English"),
        LanguageOption("zh-CN", "简体中文"),
        LanguageOption("zh-TW", "繁體中文"),
        LanguageOption("hi", "हिन्दी"),
        LanguageOption("ar", "العربية"),
        LanguageOption("pt", "Português"),
        LanguageOption("fr", "Français"),
        LanguageOption("de", "Deutsch"),
        LanguageOption("ja", "日本語"),
        LanguageOption("ko", "한국어"),
        LanguageOption("ru", "Русский"),
        LanguageOption("it", "Italiano"),
        LanguageOption("tr", "Türkçe"),
        LanguageOption("pl", "Polski"),
        LanguageOption("nl", "Nederlands"),
        LanguageOption("in", "Bahasa Indonesia"),
        LanguageOption("vi", "Tiếng Việt"),
        LanguageOption("th", "ไทย"),
        LanguageOption("uk", "Українська"),
    )

    fun savedTag(context: Context): String =
        context.getSharedPreferences("giraquest", Context.MODE_PRIVATE)
            .getString("app_language", "")
            ?.trim()
            .orEmpty()

    fun parseLocale(tag: String): Locale {
        if (tag.isBlank()) {
            val sysLocales = Resources.getSystem().configuration.locales
            return if (sysLocales.isEmpty) Locale.getDefault() else sysLocales[0]
        }
        return when (tag) {
            "zh-CN" -> Locale("zh", "CN")
            "zh-TW" -> Locale("zh", "TW")
            "id", "in" -> Locale("in")
            else -> Locale(tag)
        }
    }

    fun wrap(base: Context): Context {
        val tag = savedTag(base)
        if (tag.isBlank()) {
            val sys = parseLocale("")
            Locale.setDefault(sys)
            return base
        }
        val locale = parseLocale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    @Suppress("DEPRECATION")
    fun applyToApplication(appContext: Context) {
        val tag = savedTag(appContext)
        val locale = parseLocale(tag)
        Locale.setDefault(locale)
        val res = appContext.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        res.updateConfiguration(config, res.displayMetrics)
    }

    fun applyAndRecreate(activity: Activity, newTag: String, afterSaved: () -> Unit = {}) {
        SettingsStore(activity).setAppLanguageSync(newTag)
        applyToApplication(activity.applicationContext)
        afterSaved()
        activity.recreate()
    }

    fun bindSpinner(
        spinner: Spinner,
        activity: Activity,
        onBeforeRecreate: () -> Unit = {},
    ) {
        val currentTag = savedTag(activity)
        val labels = LANGUAGES.map { opt ->
            if (opt.tag.isEmpty()) activity.getString(R.string.language_system)
            else opt.nativeName
        }
        val adapter = ArrayAdapter(activity, R.layout.item_language_spinner, labels).apply {
            setDropDownViewResource(R.layout.item_language_dropdown)
        }
        spinner.adapter = adapter
        val idx = LANGUAGES.indexOfFirst { it.tag == currentTag }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(idx, false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val chosen = LANGUAGES.getOrNull(position)?.tag ?: return
                if (chosen == savedTag(activity)) return
                applyAndRecreate(activity, chosen, onBeforeRecreate)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
