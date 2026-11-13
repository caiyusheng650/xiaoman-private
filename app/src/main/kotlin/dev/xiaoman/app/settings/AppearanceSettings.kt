package dev.xiaoman.app.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    CHINESE("zh-CN"),
    ENGLISH("en"),
}

class AppearanceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val selectedTheme = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.SYSTEM,
    )
    private val selectedLanguage = MutableStateFlow(
        AppLanguage.entries.firstOrNull { it.name == preferences.getString("language", null) } ?: AppLanguage.SYSTEM,
    )
    val theme = selectedTheme.asStateFlow()
    val language = selectedLanguage.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        preferences.edit { putString("theme", mode.name) }
        selectedTheme.value = mode
    }

    fun setLanguage(language: AppLanguage) {
        preferences.edit { putString("language", language.name) }
        selectedLanguage.value = language
    }

    fun localizedContext(context: Context, language: AppLanguage = this.language.value): Context {
        val tag = language.tag ?: return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        val localized = context.createConfigurationContext(configuration)
        // Keep the original activity in the context chain for permission launchers and approvals.
        return object : ContextWrapper(context) {
            override fun getResources() = localized.resources
            override fun getAssets() = localized.assets
        }
    }
}
