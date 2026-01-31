package com.example.ndireceiver.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.ndireceiver.data.AppLanguage
import java.util.Locale

/**
 * Helper class for managing app locale/language settings.
 */
object LocaleHelper {

    /**
     * Apply the language setting to the app.
     * This uses the per-app language API on Android 13+ (API 33+)
     * and changes the app's configuration on older versions.
     */
    fun applyLanguage(language: AppLanguage) {
        val localeList = when (language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.JAPANESE -> LocaleListCompat.forLanguageTags("ja")
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Get the current locale based on the language setting.
     */
    fun getLocale(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.SYSTEM -> Locale.getDefault()
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.JAPANESE -> Locale.JAPANESE
        }
    }

    /**
     * Get display name for a language setting.
     */
    fun getDisplayName(context: Context, language: AppLanguage): String {
        return when (language) {
            AppLanguage.SYSTEM -> context.getString(com.example.ndireceiver.R.string.settings_language_system)
            AppLanguage.ENGLISH -> context.getString(com.example.ndireceiver.R.string.settings_language_english)
            AppLanguage.JAPANESE -> context.getString(com.example.ndireceiver.R.string.settings_language_japanese)
        }
    }
}
