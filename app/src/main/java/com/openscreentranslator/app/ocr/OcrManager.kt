package com.openscreentranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.openscreentranslator.app.data.AppLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val orderIndex: Int = 0,
    val detectedLanguage: String? = null
)

class OcrManager {
    private val TAG = "OcrManager"
    private val recognizerCache = mutableMapOf<RecognizerScript, TextRecognizer>()
    private var languageIdentifier: LanguageIdentifier? = null

    private fun scriptForLanguage(languageCode: String): RecognizerScript = when (languageCode) {
        "ja" -> RecognizerScript.JAPANESE
        "ko" -> RecognizerScript.KOREAN
        "zh" -> RecognizerScript.CHINESE
        else -> RecognizerScript.LATIN
    }

    @Synchronized
    private fun getRecognizer(script: RecognizerScript): TextRecognizer {
        return recognizerCache.getOrPut(script) {
            when (script) {
                RecognizerScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                RecognizerScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                RecognizerScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                RecognizerScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    @Synchronized
    private fun getLanguageIdentifier(): LanguageIdentifier {
        val current = languageIdentifier
        if (current != null) return current
        val created = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.45f)
                .build()
        )
        languageIdentifier = created
        return created
    }

    suspend fun processImage(
        bitmap: Bitmap,
        languageCode: String,
        comicModeEnabled: Boolean = true,
        readingDirection: ReadingDirection = ReadingDirection.MANGA_RTL
    ): List<OcrBlock> = withContext(Dispatchers.Default) {
        val origWidth = bitmap.width
        val origHeight = bitmap.height
        if (origWidth <= 0 || origHeight <= 0) return@withContext emptyList()

        // Cap oversized screenshots (e.g. tall 22:9 displays like 1080x2640) for fast, low-RAM OCR
        val scale = min(1f, min(1080f / origWidth.toFloat(), 2048f / origHeight.toFloat()))
        val ocrBitmap = if (scale < 0.98f) {
            val targetW = (origWidth * scale).roundToInt().coerceAtLeast(1)
            val targetH = (origHeight * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val scaleBackX = origWidth.toFloat() / ocrBitmap.width.toFloat()
        val scaleBackY = origHeight.toFloat() / ocrBitmap.height.toFloat()

        try {
            val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
            if (languageCode == AppLanguages.AUTO) {
                processAutoMultiScript(
                    inputImage = inputImage,
                    comicModeEnabled = comicModeEnabled,
                    readingDirection = readingDirection,
                    scaleBackX = scaleBackX,
                    scaleBackY = scaleBackY
                )
            } else {
                val script = scriptForLanguage(languageCode)
                val visionText = awaitTaskWithTimeout(getRecognizer(script).process(inputImage), 10_000L)
                    ?: return@withContext emptyList()

                val rawBlocks = toRawOcrBlocks(visionText, script, scaleBackX, scaleBackY)
                val processed = ComicBubbleProcessor.processBlocks(
                    rawBlocks = rawBlocks,
                    sourceLang = languageCode,
                    comicModeEnabled = comicModeEnabled,
                    readingDirection = readingDirection
                )
                processed.map { bubble ->
                    OcrBlock(
                        text = bubble.text,
                        boundingBox = bubble.box.toAndroidRect(),
                        orderIndex = bubble.orderIndex,
                        detectedLanguage = languageCode
                    )
                }
            }
        } finally {
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
        }
    }

    private suspend fun processAutoMultiScript(
        inputImage: InputImage,
        comicModeEnabled: Boolean,
        readingDirection: ReadingDirection,
        scaleBackX: Float,
        scaleBackY: Float
    ): List<OcrBlock> {
        val scripts = listOf(
            RecognizerScript.LATIN,
            RecognizerScript.JAPANESE,
            RecognizerScript.KOREAN,
            RecognizerScript.CHINESE
        )

        val perScriptBlocks = coroutineScope {
            scripts.map { script ->
                async(Dispatchers.Default) {
                    try {
                        val visionText = awaitTaskWithTimeout(getRecognizer(script).process(inputImage), 10_000L)
                        if (visionText != null) {
                            toRawOcrBlocks(visionText, script, scaleBackX, scaleBackY)
                        } else {
                            Log.w(TAG, "Auto OCR script $script timed out")
                            emptyList()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto OCR script $script failed: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val allCandidates = perScriptBlocks.flatten()
        if (allCandidates.isEmpty()) return emptyList()

        val fusedRaw = ComicBubbleProcessor.mergeCrossScriptCandidates(allCandidates)
        val processed = ComicBubbleProcessor.processBlocks(
            rawBlocks = fusedRaw,
            sourceLang = AppLanguages.AUTO,
            comicModeEnabled = comicModeEnabled,
            readingDirection = readingDirection
        )

        return resolveBubbleLanguages(processed)
    }

    private suspend fun resolveBubbleLanguages(
        processed: List<ProcessedBubble>
    ): List<OcrBlock> {
        if (processed.isEmpty()) return emptyList()

        val needsLangIdIndices = processed.indices.filter { processed[it].detectedLang == null }
        if (needsLangIdIndices.isEmpty()) {
            return processed.map { bubble ->
                OcrBlock(
                    text = bubble.text,
                    boundingBox = bubble.box.toAndroidRect(),
                    orderIndex = bubble.orderIndex,
                    detectedLanguage = bubble.detectedLang
                )
            }
        }

        val identifier = getLanguageIdentifier()
        val detectedByIdx = coroutineScope {
            needsLangIdIndices.map { idx ->
                async(Dispatchers.Default) {
                    val tag = try {
                        awaitTaskWithTimeout(identifier.identifyLanguage(processed[idx].text), 2_500L)
                    } catch (_: Exception) {
                        null
                    }
                    idx to AppLanguages.normalizeDetectedTag(tag)
                }
            }.awaitAll().toMap()
        }

        return processed.mapIndexed { idx, bubble ->
            val detected = bubble.detectedLang ?: detectedByIdx[idx]
            OcrBlock(
                text = bubble.text,
                boundingBox = bubble.box.toAndroidRect(),
                orderIndex = bubble.orderIndex,
                detectedLanguage = detected
            )
        }
    }

    private suspend fun <T> awaitTaskWithTimeout(task: Task<T>, timeoutMs: Long): T? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                task.addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }.addOnFailureListener { err ->
                    if (cont.isActive) cont.resumeWithException(err)
                }.addOnCanceledListener {
                    if (cont.isActive) cont.cancel()
                }
            }
        }
    }

    private fun toRawOcrBlocks(
        visionText: Text,
        script: RecognizerScript,
        scaleBackX: Float,
        scaleBackY: Float
    ): List<RawOcrBlock> {
        return visionText.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val rawText = block.text.trim()
            if (rawText.isBlank()) return@mapNotNull null

            val lines = block.lines.mapNotNull inner@{ line ->
                val lineBox = line.boundingBox ?: return@inner null
                val lineText = line.text.trim()
                if (lineText.isBlank()) null else OcrLine(lineText, lineBox.toScaledRectBox(scaleBackX, scaleBackY))
            }
            RawOcrBlock(
                text = rawText,
                box = box.toScaledRectBox(scaleBackX, scaleBackY),
                lines = lines,
                scriptHint = script
            )
        }
    }

    @Synchronized
    fun close() {
        recognizerCache.values.forEach { it.close() }
        recognizerCache.clear()
        languageIdentifier?.close()
        languageIdentifier = null
    }

    private fun Rect.toScaledRectBox(sx: Float, sy: Float): RectBox = RectBox(
        left = (left * sx).roundToInt(),
        top = (top * sy).roundToInt(),
        right = (right * sx).roundToInt(),
        bottom = (bottom * sy).roundToInt()
    )

    private fun RectBox.toAndroidRect(): Rect = Rect(left, top, right, bottom)
}
