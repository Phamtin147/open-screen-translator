package com.openscreentranslator.app.translation

import android.util.Log
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TranslationManager {
    private val TAG = "TranslationManager"
    private val translatorCache = mutableMapOf<String, Translator>()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
        engineMode: String = "on_device",
        geminiApiKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        when (engineMode) {
            "gemini_ai" -> {
                translateViaGeminiAI(text, sourceLang, targetLang, geminiApiKey)
            }
            "on_device" -> {
                try {
                    translateOnDevice(text, sourceLang, targetLang)
                } catch (e: Exception) {
                    Log.w(TAG, "On-device translation failed, falling back to free cloud: ${e.message}")
                    translateViaFreeCloud(text, sourceLang, targetLang)
                }
            }
            else -> {
                translateViaFreeCloud(text, sourceLang, targetLang)
            }
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

    private fun translateViaGeminiAI(
        text: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String
    ): String {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Gemini API key is blank! Falling back to Free Cloud")
            return translateViaFreeCloud(text, sourceLang, targetLang)
        }

        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val prompt = "You are a professional screen translator for games and comics. Translate the following text from '$sourceLang' into '$targetLang' naturally and concisely. Output ONLY the translated text without any explanation, quotes or preamble:\n\n$text"

            val jsonPayload = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            val partObj = JSONObject().apply {
                                put("text", prompt)
                            }
                            put(partObj)
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return translateViaFreeCloud(text, sourceLang, targetLang)

            val rootJson = JSONObject(body)
            if (rootJson.has("candidates")) {
                val candidates = rootJson.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        val translated = parts.getJSONObject(0).getString("text").trim()
                        if (translated.isNotBlank()) return translated
                    }
                }
            }
            translateViaFreeCloud(text, sourceLang, targetLang)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API call failed, falling back to Free Cloud", e)
            translateViaFreeCloud(text, sourceLang, targetLang)
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
