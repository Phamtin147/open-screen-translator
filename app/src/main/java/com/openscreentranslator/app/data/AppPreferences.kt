package com.openscreentranslator.app.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("open_screen_translator_prefs", Context.MODE_PRIVATE)

    var sourceLanguage: String
        get() = prefs.getString("source_language", "en") ?: "en"
        set(value) = prefs.edit().putString("source_language", value).apply()

    var targetLanguage: String
        get() = prefs.getString("target_language", "vi") ?: "vi"
        set(value) = prefs.edit().putString("target_language", value).apply()

    var engineMode: String
        get() = prefs.getString("engine_mode", "on_device") ?: "on_device"
        set(value) = prefs.edit().putString("engine_mode", value).apply()

    var overlayOpacity: Int
        get() = prefs.getInt("overlay_opacity", 90)
        set(value) = prefs.edit().putInt("overlay_opacity", value).apply()

    var textSize: Int
        get() = prefs.getInt("text_size", 14)
        set(value) = prefs.edit().putInt("text_size", value).apply()

    var autoClearSeconds: Int
        get() = prefs.getInt("auto_clear_seconds", 8)
        set(value) = prefs.edit().putInt("auto_clear_seconds", value).apply()
}
