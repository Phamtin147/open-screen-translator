package com.openscreentranslator.app.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("open_screen_translator_prefs", Context.MODE_PRIVATE)

    var sourceLanguage: String
        get() = prefs.getString("source_language", AppLanguages.AUTO) ?: AppLanguages.AUTO
        set(value) = prefs.edit().putString("source_language", value).apply()

    var targetLanguage: String
        get() = prefs.getString("target_language", AppLanguages.DEFAULT_TARGET) ?: AppLanguages.DEFAULT_TARGET
        set(value) = prefs.edit().putString("target_language", value).apply()

    /**
     * Engines:
     * - "hy_mt2": On-device Hy-MT2-1.8B GGUF via llama.cpp
     * - "on_device": Google ML Kit on-device translator
     * - "gemini_ai": Google Gemini API (user key)
     * - "cloud_free": Google Translate HTTP free endpoint
     */
    var engineMode: String
        get() = prefs.getString("engine_mode", ENGINE_ML_KIT) ?: ENGINE_ML_KIT
        set(value) = prefs.edit().putString("engine_mode", value).apply()

    var overlayOpacity: Int
        get() = prefs.getInt("overlay_opacity", 92).coerceIn(65, 100)
        set(value) = prefs.edit().putInt("overlay_opacity", value.coerceIn(65, 100)).apply()

    var textSize: Int
        get() = prefs.getInt("text_size", 14)
        set(value) = prefs.edit().putInt("text_size", value).apply()

    var autoClearSeconds: Int
        get() = prefs.getInt("auto_clear_seconds", 0)
        set(value) = prefs.edit().putInt("auto_clear_seconds", value).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value).apply()

    var comicModeEnabled: Boolean
        get() = prefs.getBoolean("comic_mode_enabled", true)
        set(value) = prefs.edit().putBoolean("comic_mode_enabled", value).apply()

    /** "rtl" (Manga Right-to-Left) or "ltr" (Comic/Webtoon Left-to-Right) */
    var readingDirection: String
        get() = prefs.getString("reading_direction", DIRECTION_RTL) ?: DIRECTION_RTL
        set(value) = prefs.edit().putString("reading_direction", value).apply()

    var wholePageContextEnabled: Boolean
        get() = prefs.getBoolean("whole_page_context_enabled", true)
        set(value) = prefs.edit().putBoolean("whole_page_context_enabled", value).apply()

    var hymtModelVariant: String
        get() = prefs.getString("hymt_model_variant", "Q3_K_S") ?: "Q3_K_S"
        set(value) = prefs.edit().putString("hymt_model_variant", value).apply()

    /** "system" | "light" | "dark" */
    var themeMode: String
        get() = prefs.getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    companion object {
        const val ENGINE_HY_MT2 = "hy_mt2"
        const val ENGINE_ML_KIT = "on_device"
        const val ENGINE_GEMINI = "gemini_ai"
        const val ENGINE_CLOUD_FREE = "cloud_free"

        const val DIRECTION_RTL = "rtl"
        const val DIRECTION_LTR = "ltr"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
