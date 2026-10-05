package com.openscreentranslator.app.translation

import android.util.Log
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TranslationManager {
    private val TAG = "TranslationManager"
    private val translatorCache = mutableMapOf<String, Translator>()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun getOnDeviceTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "${sourceLang}_${targetLang}"
        return translatorCache.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    suspend fun translateText(
        text: String,
        sourceLang: String,
        targetLang: String,
        engineMode: String = "on_device"
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        if (engineMode == "on_device") {
            try {
                return@withContext translateOnDevice(text, sourceLang, targetLang)
            } catch (e: Exception) {
                Log.w(TAG, "On-device translation failed, falling back to free cloud endpoint: ${e.message}")
                return@withContext translateViaFreeCloud(text, sourceLang, targetLang)
            }
        } else {
            return@withContext translateViaFreeCloud(text, sourceLang, targetLang)
        }
    }

    private suspend fun translateOnDevice(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.Default) {
        kotlin.coroutines.suspendCoroutine { continuation ->
            val translator = getOnDeviceTranslator(sourceLang, targetLang)
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { result ->
                            continuation.resumeWith(Result.success(result))
                        }
                        .addOnFailureListener { e ->
                            continuation.resumeWith(Result.failure(e))
                        }
                }
                .addOnFailureListener { e ->
                    continuation.resumeWith(Result.failure(e))
                }
        }
    }

    private fun translateViaFreeCloud(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String {
        return try {
            val encodedQuery = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return text

            val jsonArray = JSONArray(body)
            val sentences = jsonArray.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONArray(i)
                sb.append(sentence.getString(0))
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Free cloud translation error", e)
            text
        }
    }

    fun close() {
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
    }
}
