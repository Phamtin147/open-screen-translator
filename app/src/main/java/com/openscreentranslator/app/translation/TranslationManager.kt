package com.openscreentranslator.app.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.openscreentranslator.app.data.AppLanguages
import com.openscreentranslator.app.data.AppPreferences
import com.openscreentranslator.app.ocr.OcrBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PageTranslationResult(
    val translatedBlocks: List<Pair<String, android.graphics.Rect>>,
    val usedEngine: String,
    val fallbackNotice: String? = null
)

class TranslationManager(private val appContext: Context? = null) {
    private val TAG = "TranslationManager"
    private val mlKitTranslators = mutableMapOf<String, Translator>()
    private val networkSemaphore = Semaphore(6)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun translatePageBubbles(
        blocks: List<OcrBlock>,
        sourceLang: String,
        targetLang: String,
        engineMode: String,
        geminiApiKey: String = "",
        wholePageContextEnabled: Boolean = true,
        preferredHymtVariant: String = "Q3_K_S"
    ): PageTranslationResult = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) {
            return@withContext PageTranslationResult(emptyList(), engineMode)
        }

        if (sourceLang != AppLanguages.AUTO && sourceLang == targetLang) {
            return@withContext PageTranslationResult(
                blocks.map { it.text to it.boundingBox },
                engineMode
            )
        }

        if (blocks.all { it.detectedLanguage == targetLang }) {
            return@withContext PageTranslationResult(
                blocks.map { it.text to it.boundingBox },
                engineMode
            )
        }

        when (engineMode) {
            AppPreferences.ENGINE_HY_MT2 -> {
                val context = appContext
                if (context == null || !HymtEngineHolder.ensureLoaded(context, preferredHymtVariant)) {
                    val notice = "Chưa tải hoặc nạp được Hy-MT2 GGUF — tạm dịch bằng ML Kit"
                    val fallback = translateViaMlKitOrCloud(blocks, sourceLang, targetLang)
                    return@withContext PageTranslationResult(
                        translatedBlocks = fallback,
                        usedEngine = AppPreferences.ENGINE_ML_KIT,
                        fallbackNotice = notice
                    )
                }

                val translations = translateBlocksWithHymt(
                    blocks = blocks,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    wholePageContextEnabled = wholePageContextEnabled
                )
                PageTranslationResult(translations, AppPreferences.ENGINE_HY_MT2)
            }

            AppPreferences.ENGINE_GEMINI -> {
                if (geminiApiKey.isBlank()) {
                    val fallback = translateViaMlKitOrCloud(blocks, sourceLang, targetLang)
                    return@withContext PageTranslationResult(
                        translatedBlocks = fallback,
                        usedEngine = AppPreferences.ENGINE_ML_KIT,
                        fallbackNotice = "Chưa nhập Gemini API Key — tạm dùng dịch Offline"
                    )
                }

                val translations = translateBlocksWithGemini(
                    blocks = blocks,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    apiKey = geminiApiKey,
                    wholePageContextEnabled = wholePageContextEnabled
                )
                PageTranslationResult(translations, AppPreferences.ENGINE_GEMINI)
            }

            AppPreferences.ENGINE_ML_KIT -> {
                val fellBackToCloud = AtomicBoolean(false)
                val pairs = translateViaMlKitOrCloud(blocks, sourceLang, targetLang, fellBackToCloud)
                val notice = if (fellBackToCloud.get()) {
                    "Gói offline ML Kit đang tải hoặc chưa sẵn sàng — tạm dịch qua Cloud"
                } else {
                    null
                }
                PageTranslationResult(pairs, AppPreferences.ENGINE_ML_KIT, fallbackNotice = notice)
            }

            else -> {
                val pairs = coroutineScope {
                    blocks.map { block ->
                        async {
                            if (block.detectedLanguage == targetLang) {
                                block.text to block.boundingBox
                            } else {
                                val sl = if (sourceLang == AppLanguages.AUTO) {
                                    AppLanguages.AUTO
                                } else {
                                    block.detectedLanguage ?: sourceLang
                                }
                                val translated = networkSemaphore.withPermit {
                                    translateViaFreeCloud(block.text, sl, targetLang)
                                }
                                translated to block.boundingBox
                            }
                        }
                    }.awaitAll()
                }
                PageTranslationResult(pairs, AppPreferences.ENGINE_CLOUD_FREE)
            }
        }
    }

    suspend fun translateText(
        text: String,
        sourceLang: String,
        targetLang: String,
        engineMode: String = AppPreferences.ENGINE_ML_KIT,
        geminiApiKey: String = "",
        preferredHymtVariant: String = "Q3_K_S"
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        if (sourceLang != AppLanguages.AUTO && sourceLang == targetLang) return@withContext text

        when (engineMode) {
            AppPreferences.ENGINE_HY_MT2 -> {
                val context = appContext
                if (context != null && HymtEngineHolder.ensureLoaded(context, preferredHymtVariant)) {
                    val prompt = TranslationBatchHelper.buildSingleHymtPrompt(text, targetLang)
                    val out = LlamaBridge.nativeTranslate(prompt, 320, 0.3f)
                    val cleaned = TranslationBatchHelper.cleanSingleOutput(out.orEmpty())
                    if (cleaned.isNotBlank()) return@withContext cleaned
                }
                translateSingleMlKitOrCloud(text, sourceLang, targetLang)
            }

            AppPreferences.ENGINE_GEMINI -> {
                translateViaGeminiSingle(text, sourceLang, targetLang, geminiApiKey)
            }

            AppPreferences.ENGINE_ML_KIT -> {
                translateSingleMlKitOrCloud(text, sourceLang, targetLang)
            }

            else -> translateViaFreeCloud(text, sourceLang, targetLang)
        }
    }

    private suspend fun translateBlocksWithHymt(
        blocks: List<OcrBlock>,
        sourceLang: String,
        targetLang: String,
        wholePageContextEnabled: Boolean
    ): List<Pair<String, android.graphics.Rect>> {
        val allTexts = blocks.map { it.text }
        val pendingIndices = blocks.indices.filter { blocks[it].detectedLanguage != targetLang }
        if (pendingIndices.isEmpty()) {
            return blocks.map { it.text to it.boundingBox }
        }

        if (pendingIndices.size == 1 || !wholePageContextEnabled) {
            return blocks.map { block ->
                coroutineContext.ensureActive()
                if (block.detectedLanguage == targetLang) {
                    block.text to block.boundingBox
                } else {
                    val prompt = if (wholePageContextEnabled && blocks.size > 1) {
                        TranslationBatchHelper.buildContextSingleHymtPrompt(block.text, allTexts, targetLang)
                    } else {
                        TranslationBatchHelper.buildSingleHymtPrompt(block.text, targetLang)
                    }
                    val raw = LlamaBridge.nativeTranslate(prompt, 384, 0.3f)
                    val cleaned = TranslationBatchHelper.cleanSingleOutput(raw.orEmpty())
                    (cleaned.ifBlank { block.text }) to block.boundingBox
                }
            }
        }

        // Batch sequentially in chunks of up to 8 untranslated bubbles so n_ctx=2048 stays sharp and fast
        val resolved = MutableList<String?>(blocks.size) { idx ->
            if (blocks[idx].detectedLanguage == targetLang) blocks[idx].text else null
        }
        val chunkSize = 8
        var offset = 0
        while (offset < pendingIndices.size) {
            coroutineContext.ensureActive()
            val chunkIndices = pendingIndices.subList(offset, minOf(offset + chunkSize, pendingIndices.size))
            if (chunkIndices.size == 1) {
                val idx = chunkIndices[0]
                val singlePrompt = TranslationBatchHelper.buildContextSingleHymtPrompt(
                    sourceText = blocks[idx].text,
                    pageContextLines = allTexts,
                    targetLang = targetLang
                )
                val out = TranslationBatchHelper.cleanSingleOutput(
                    LlamaBridge.nativeTranslate(singlePrompt, 384, 0.3f).orEmpty()
                )
                if (out.isNotBlank()) {
                    resolved[idx] = out
                }
            } else {
                val chunkTexts = chunkIndices.map { blocks[it].text }
                val chunkLangs = chunkIndices.map { blocks[it].detectedLanguage }
                val prompt = TranslationBatchHelper.buildTaggedBatchPrompt(
                    bubbles = chunkTexts,
                    targetLang = targetLang,
                    sourceLang = sourceLang,
                    includeContextInstruction = true,
                    bubbleDetectedLangs = chunkLangs
                )
                val maxOutTokens = (chunkTexts.sumOf { it.length } * 2 + 128).coerceIn(256, 1024)
                val raw = LlamaBridge.nativeTranslate(prompt, maxOutTokens, 0.25f).orEmpty()
                val parsed = TranslationBatchHelper.parseTaggedBatchResponse(raw, chunkTexts.size)
                for (i in chunkIndices.indices) {
                    val item = parsed[i + 1]
                    if (!item.isNullOrBlank()) {
                        resolved[chunkIndices[i]] = item
                    }
                }
            }
            offset += chunkSize
        }

        // Recover any bubble omitted from the batch delimiter output using page background context
        for (i in pendingIndices) {
            coroutineContext.ensureActive()
            if (resolved[i].isNullOrBlank()) {
                val prompt = TranslationBatchHelper.buildContextSingleHymtPrompt(
                    sourceText = blocks[i].text,
                    pageContextLines = allTexts,
                    targetLang = targetLang
                )
                val raw = LlamaBridge.nativeTranslate(prompt, 384, 0.3f).orEmpty()
                val cleaned = TranslationBatchHelper.cleanSingleOutput(raw)
                resolved[i] = cleaned.ifBlank { blocks[i].text }
            }
        }

        return blocks.mapIndexed { idx, block ->
            (resolved[idx] ?: block.text) to block.boundingBox
        }
    }

    private suspend fun translateBlocksWithGemini(
        blocks: List<OcrBlock>,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        wholePageContextEnabled: Boolean
    ): List<Pair<String, android.graphics.Rect>> {
        val pendingIndices = blocks.indices.filter { blocks[it].detectedLanguage != targetLang }
        if (pendingIndices.isEmpty()) {
            return blocks.map { it.text to it.boundingBox }
        }

        if (pendingIndices.size == 1 || !wholePageContextEnabled) {
            return coroutineScope {
                blocks.map { block ->
                    async {
                        if (block.detectedLanguage == targetLang) {
                            block.text to block.boundingBox
                        } else {
                            val effSrc = block.detectedLanguage ?: sourceLang
                            val t = translateViaGeminiSingle(block.text, effSrc, targetLang, apiKey)
                            t to block.boundingBox
                        }
                    }
                }.awaitAll()
            }
        }

        val prompt = TranslationBatchHelper.buildTaggedBatchPrompt(
            bubbles = pendingIndices.map { blocks[it].text },
            targetLang = targetLang,
            sourceLang = sourceLang,
            includeContextInstruction = true,
            bubbleDetectedLangs = pendingIndices.map { blocks[it].detectedLanguage }
        )

        val batchResponse = callGeminiPrompt(prompt, apiKey)
        val parsed = if (batchResponse != null) {
            TranslationBatchHelper.parseTaggedBatchResponse(batchResponse, pendingIndices.size)
        } else {
            emptyMap()
        }

        val pendingPositionMap = pendingIndices.withIndex().associate { (pos, origIdx) -> origIdx to (pos + 1) }

        return coroutineScope {
            blocks.mapIndexed { index, block ->
                async {
                    if (block.detectedLanguage == targetLang) {
                        block.text to block.boundingBox
                    } else {
                        val batchPos = pendingPositionMap[index]
                        val existing = if (batchPos != null) parsed[batchPos] else null
                        if (!existing.isNullOrBlank()) {
                            existing to block.boundingBox
                        } else {
                            val effSrc = block.detectedLanguage ?: sourceLang
                            val single = translateViaGeminiSingle(block.text, effSrc, targetLang, apiKey)
                            single to block.boundingBox
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun translateViaMlKitOrCloud(
        blocks: List<OcrBlock>,
        sourceLang: String,
        targetLang: String,
        fellBackToCloud: AtomicBoolean? = null
    ): List<Pair<String, android.graphics.Rect>> = coroutineScope {
        blocks.map { block ->
            async {
                val effectiveSource = when {
                    !block.detectedLanguage.isNullOrBlank() -> block.detectedLanguage
                    sourceLang != AppLanguages.AUTO -> sourceLang
                    else -> AppLanguages.AUTO
                }
                if (effectiveSource == targetLang) {
                    block.text to block.boundingBox
                } else {
                    val t = translateSingleMlKitOrCloud(block.text, effectiveSource, targetLang, fellBackToCloud)
                    t to block.boundingBox
                }
            }
        }.awaitAll()
    }

    private suspend fun translateSingleMlKitOrCloud(
        text: String,
        sourceLang: String,
        targetLang: String,
        fellBackToCloud: AtomicBoolean? = null
    ): String {
        if (sourceLang == targetLang) return text
        val mlSource = mapToMlKitLanguage(sourceLang)
        val mlTarget = mapToMlKitLanguage(targetLang)
        if (mlSource == null || mlTarget == null) {
            return networkSemaphore.withPermit {
                translateViaFreeCloud(
                    text = text,
                    sourceLang = sourceLang.ifBlank { AppLanguages.AUTO },
                    targetLang = targetLang
                )
            }
        }

        return try {
            translateOnDevice(text, mlSource, mlTarget)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit ($mlSource->$mlTarget) failed or timed out, falling back to Cloud Free: ${e.message}")
            fellBackToCloud?.set(true)
            networkSemaphore.withPermit {
                translateViaFreeCloud(text, sourceLang, targetLang)
            }
        }
    }

    private suspend fun translateOnDevice(
        text: String,
        mlSource: String,
        mlTarget: String
    ): String {
        val key = "$mlSource-$mlTarget"

        val translator = synchronized(mlKitTranslators) {
            mlKitTranslators.getOrPut(key) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlSource)
                    .setTargetLanguage(mlTarget)
                    .build()
                Translation.getClient(options)
            }
        }

        val conditions = DownloadConditions.Builder().build()
        withTimeout(12_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                    .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
            }
        }

        return withTimeout(8_000L) {
            suspendCancellableCoroutine { cont ->
                translator.translate(text)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) cont.resume(result.ifBlank { text })
                    }
                    .addOnFailureListener { e ->
                        if (cont.isActive) cont.resumeWithException(e)
                    }
            }
        }
    }

    private fun translateViaGeminiSingle(
        text: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String
    ): String {
        if (apiKey.isBlank()) {
            return "Lỗi: Chưa nhập Gemini API Key."
        }
        val prompt = TranslationBatchHelper.buildGeminiSinglePrompt(
            sourceText = text,
            sourceLang = sourceLang,
            targetLang = targetLang
        )
        return callGeminiPrompt(prompt, apiKey) ?: "[Lỗi Gemini] Không thể dịch."
    }

    private fun callGeminiPrompt(prompt: String, apiKey: String): String? {
        val modelsToTry = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        for (modelName in modelsToTry) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.25)
                    })
                }
                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(requestBody).build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.code == 404) {
                        Log.w(TAG, "Gemini model $modelName returned 404, trying fallback model")
                        return@use
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Gemini error (${response.code}): $bodyStr")
                        return "[Lỗi Gemini API: ${response.code}]"
                    }
                    val root = JSONObject(bodyStr)
                    val candidates = root.optJSONArray("candidates") ?: return null
                    if (candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return parts.getJSONObject(0).optString("text", "").trim()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini network error with $modelName", e)
            }
        }
        return null
    }

    private fun translateViaFreeCloud(text: String, sourceLang: String, targetLang: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val sl = sourceLang.ifBlank { AppLanguages.AUTO }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$targetLang&dt=t&q=$encodedText"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return text
                val responseBody = response.body?.string() ?: return text
                val jsonArray = JSONArray(responseBody)
                val sentencesArray = jsonArray.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val sentence = sentencesArray.getJSONArray(i)
                    sb.append(sentence.optString(0, ""))
                }
                sb.toString().trim().ifBlank { text }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Free Cloud translation error", e)
            text
        }
    }

    private fun mapToMlKitLanguage(code: String): String? {
        if (code.isBlank() || code == AppLanguages.AUTO) return null
        return when (code.lowercase()) {
            "vi" -> TranslateLanguage.VIETNAMESE
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "zh" -> TranslateLanguage.CHINESE
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH
            "pt" -> TranslateLanguage.PORTUGUESE
            "id" -> TranslateLanguage.INDONESIAN
            "it" -> TranslateLanguage.ITALIAN
            "nl" -> TranslateLanguage.DUTCH
            "pl" -> TranslateLanguage.POLISH
            "tr" -> TranslateLanguage.TURKISH
            "ru" -> TranslateLanguage.RUSSIAN
            "th" -> TranslateLanguage.THAI
            "ar" -> TranslateLanguage.ARABIC
            else -> TranslateLanguage.fromLanguageTag(code)
        }
    }

    fun close() {
        synchronized(mlKitTranslators) {
            mlKitTranslators.values.forEach { it.close() }
            mlKitTranslators.clear()
        }
    }
}
