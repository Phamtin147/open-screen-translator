package com.openscreentranslator.app.translation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.openscreentranslator.app.data.AppLanguages
import com.openscreentranslator.app.data.AppPreferences
import com.openscreentranslator.app.ocr.OcrBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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

private class MlKitTimeoutException(message: String) : IllegalStateException(message)

class TranslationManager(private val appContext: Context? = null) {
    private val TAG = "TranslationManager"
    private val mlKitTranslators = mutableMapOf<String, Translator>()
    private val mlKitPairMutexes = mutableMapOf<String, Mutex>()
    private val readyMlKitPairs = ConcurrentHashMap.newKeySet<String>()
    private val networkSemaphore = Semaphore(6)

    private val httpClient = OkHttpClient.Builder()
        .dns(ResilientDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun shouldSkipAsAlreadyTarget(block: OcrBlock, targetLang: String): Boolean {
        if (block.detectedLanguage != targetLang) return false
        if (targetLang == "vi" && !AppLanguages.hasVietnameseDiacritics(block.text) && block.text.length <= 20) {
            return false
        }
        return true
    }

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

        if (blocks.all { shouldSkipAsAlreadyTarget(it, targetLang) }) {
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
                    targetLang = targetLang
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
                val cloudFailedOffline = AtomicBoolean(false)
                val pairs = translateViaMlKitOrCloud(
                    blocks = blocks,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    fellBackToCloud = fellBackToCloud,
                    cloudFailedOffline = cloudFailedOffline
                )
                val notice = when {
                    cloudFailedOffline.get() ->
                        "Gói offline ML Kit chưa sẵn & không có mạng — bấm 'Tải gói offline' trong app trước"
                    fellBackToCloud.get() ->
                        "Gói offline ML Kit chưa sẵn — đã tạm dịch qua Google Cloud"
                    else -> null
                }
                PageTranslationResult(pairs, AppPreferences.ENGINE_ML_KIT, fallbackNotice = notice)
            }

            else -> {
                val cloudFailedOffline = AtomicBoolean(false)
                val pairs = coroutineScope {
                    blocks.map { block ->
                        async {
                            if (shouldSkipAsAlreadyTarget(block, targetLang)) {
                                block.text to block.boundingBox
                            } else {
                                val sl = if (sourceLang == AppLanguages.AUTO) {
                                    AppLanguages.AUTO
                                } else {
                                    block.detectedLanguage ?: sourceLang
                                }
                                val translated = networkSemaphore.withPermit {
                                    translateViaFreeCloud(block.text, sl, targetLang, cloudFailedOffline)
                                }
                                translated to block.boundingBox
                            }
                        }
                    }.awaitAll()
                }
                val notice = if (cloudFailedOffline.get()) {
                    "Không kết nối được Google Translate Cloud — kiểm tra Wi-Fi/4G"
                } else null
                PageTranslationResult(pairs, AppPreferences.ENGINE_CLOUD_FREE, fallbackNotice = notice)
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
                if (TranslationBatchHelper.shouldSkipTranslation(text)) return@withContext text
                val context = appContext
                if (context != null && HymtEngineHolder.ensureLoaded(context, preferredHymtVariant)) {
                    val cleaned = translateSingleHymtSegment(text, targetLang)
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

    private fun translateSingleHymtSegment(text: String, targetLang: String): String {
        val normalized = TranslationBatchHelper.normalizeComicTextForHymt(text)
        if (normalized.isEmpty()) return ""
        val prompt = TranslationBatchHelper.buildSingleHymtPrompt(normalized, targetLang)
        val maxOutTokens = (normalized.length * 2 + 28).coerceIn(32, 200)
        val raw = LlamaBridge.nativeTranslate(prompt, maxOutTokens, 0.0f).orEmpty()
        return TranslationBatchHelper.stripRedundantOuterQuotes(
            sourceText = normalized,
            translated = TranslationBatchHelper.cleanSingleOutput(raw)
        )
    }

    private suspend fun translateBlocksWithHymt(
        blocks: List<OcrBlock>,
        targetLang: String
    ): List<Pair<String, android.graphics.Rect>> {
        val hasPendingWork = blocks.any {
            it.detectedLanguage != targetLang &&
                !TranslationBatchHelper.shouldSkipTranslation(it.text)
        }
        if (!hasPendingWork) {
            return blocks.map { it.text to it.boundingBox }
        }

        // Hy-MT2-1.8B is tuned strictly for single-segment translation; 1-to-1 normalized prompts with
        // native KV prefix reuse avoid [[B1]] tag-scrambling, cross-bubble leakage, and recovery retries.
        return blocks.map { block ->
            coroutineContext.ensureActive()
            if (block.detectedLanguage == targetLang || TranslationBatchHelper.shouldSkipTranslation(block.text)) {
                block.text to block.boundingBox
            } else {
                val cleaned = translateSingleHymtSegment(block.text, targetLang)
                (cleaned.ifBlank { block.text }) to block.boundingBox
            }
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
        fellBackToCloud: AtomicBoolean? = null,
        cloudFailedOffline: AtomicBoolean? = null
    ): List<Pair<String, android.graphics.Rect>> = coroutineScope {
        blocks.map { block ->
            async {
                if (shouldSkipAsAlreadyTarget(block, targetLang)) {
                    block.text to block.boundingBox
                } else {
                    val effectiveSource = when {
                        !block.detectedLanguage.isNullOrBlank() -> block.detectedLanguage
                        sourceLang != AppLanguages.AUTO -> sourceLang
                        else -> AppLanguages.AUTO
                    }
                    val t = translateSingleMlKitOrCloud(
                        text = block.text,
                        sourceLang = effectiveSource,
                        targetLang = targetLang,
                        fellBackToCloud = fellBackToCloud,
                        cloudFailedOffline = cloudFailedOffline
                    )
                    t to block.boundingBox
                }
            }
        }.awaitAll()
    }

    private suspend fun translateSingleMlKitOrCloud(
        text: String,
        sourceLang: String,
        targetLang: String,
        fellBackToCloud: AtomicBoolean? = null,
        cloudFailedOffline: AtomicBoolean? = null
    ): String {
        if (sourceLang == targetLang) return text
        val mlSource = mapToMlKitLanguage(sourceLang)
        val mlTarget = mapToMlKitLanguage(targetLang)
        if (mlSource == null || mlTarget == null) {
            return networkSemaphore.withPermit {
                translateViaFreeCloud(
                    text = text,
                    sourceLang = sourceLang.ifBlank { AppLanguages.AUTO },
                    targetLang = targetLang,
                    cloudFailedOffline = cloudFailedOffline
                )
            }
        }

        return try {
            translateOnDevice(text, mlSource, mlTarget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit ($mlSource->$mlTarget) failed or timed out, falling back to Cloud Free: ${e.message}")
            fellBackToCloud?.set(true)
            networkSemaphore.withPermit {
                translateViaFreeCloud(text, sourceLang, targetLang, cloudFailedOffline)
            }
        }
    }

    private suspend fun translateOnDevice(
        text: String,
        mlSource: String,
        mlTarget: String
    ): String {
        val key = "$mlSource-$mlTarget"
        coroutineContext.ensureActive()

        val translator = synchronized(mlKitTranslators) {
            mlKitTranslators.getOrPut(key) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlSource)
                    .setTargetLanguage(mlTarget)
                    .build()
                Translation.getClient(options)
            }
        }

        if (!readyMlKitPairs.contains(key)) {
            val pairMutex = synchronized(mlKitPairMutexes) {
                mlKitPairMutexes.getOrPut(key) { Mutex() }
            }
            pairMutex.withLock {
                if (!readyMlKitPairs.contains(key)) {
                    val conditions = DownloadConditions.Builder().build()
                    val downloaded = withTimeoutOrNull(14_000L) {
                        awaitTask(translator.downloadModelIfNeeded(conditions))
                        true
                    }
                    coroutineContext.ensureActive()
                    if (downloaded != true) {
                        throw MlKitTimeoutException("ML Kit download timed out for $key")
                    }
                    readyMlKitPairs.add(key)
                }
            }
        }

        val translated = withTimeoutOrNull(10_000L) {
            awaitTask(translator.translate(text))
        }
        coroutineContext.ensureActive()
        if (translated == null) {
            throw MlKitTimeoutException("ML Kit translate timed out for $key")
        }
        return translated.ifBlank { text }
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

    private fun translateViaFreeCloud(
        text: String,
        sourceLang: String,
        targetLang: String,
        cloudFailedOffline: AtomicBoolean? = null
    ): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val sl = sourceLang.ifBlank { AppLanguages.AUTO }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$targetLang&dt=t&q=$encodedText"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    cloudFailedOffline?.set(true)
                    return text
                }
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
            cloudFailedOffline?.set(true)
            text
        }
    }

    fun close() {
        synchronized(mlKitTranslators) {
            mlKitTranslators.values.forEach { it.close() }
            mlKitTranslators.clear()
        }
        readyMlKitPairs.clear()
    }

    companion object {
        fun mapToMlKitLanguage(code: String): String? {
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

        private suspend fun <T> awaitTask(task: Task<T>): T {
            return suspendCancellableCoroutine { cont ->
                task.addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }.addOnFailureListener { err ->
                    if (cont.isActive) cont.resumeWithException(err)
                }.addOnCanceledListener {
                    if (cont.isActive) cont.cancel()
                }
            }
        }

        suspend fun listDownloadedMlKitCodes(): Set<String> = withContext(Dispatchers.IO) {
            try {
                val models = awaitTask(
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                )
                models.map { it.language.lowercase() }.toSet()
            } catch (e: Exception) {
                Log.w("TranslationManager", "Failed to query ML Kit models", e)
                emptySet()
            }
        }

        suspend fun downloadMlKitLanguages(
            appCodes: List<String>,
            onProgress: (done: Int, total: Int, code: String) -> Unit = { _, _, _ -> }
        ): Result<Set<String>> = withContext(Dispatchers.IO) {
            val mlCodes = appCodes.mapNotNull { mapToMlKitLanguage(it) }.distinct()
            if (mlCodes.isEmpty()) return@withContext Result.success(emptySet())

            val remoteManager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder().build()
            val already = try {
                awaitTask(remoteManager.getDownloadedModels(TranslateRemoteModel::class.java))
                    .map { it.language.lowercase() }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }

            val missing = mlCodes.filter { it.lowercase() !in already }
            if (missing.isEmpty()) {
                return@withContext Result.success(already)
            }

            return@withContext try {
                missing.forEachIndexed { idx, mlCode ->
                    coroutineContext.ensureActive()
                    onProgress(idx + 1, missing.size, mlCode)
                    val model = TranslateRemoteModel.Builder(mlCode).build()
                    val ok = withTimeoutOrNull(90_000L) {
                        awaitTask(remoteManager.download(model, conditions))
                        true
                    }
                    coroutineContext.ensureActive()
                    if (ok != true) {
                        throw MlKitTimeoutException("Tải gói ML Kit '$mlCode' bị quá thời gian (kiểm tra mạng)")
                    }
                }
                val updated = listDownloadedMlKitCodes()
                Result.success(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
