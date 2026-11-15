package com.openscreentranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
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

    fun processImage(
        bitmap: Bitmap,
        languageCode: String,
        comicModeEnabled: Boolean = true,
        readingDirection: ReadingDirection = ReadingDirection.MANGA_RTL,
        onSuccess: (List<OcrBlock>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        if (languageCode == AppLanguages.AUTO) {
            processAutoMultiScript(
                inputImage = inputImage,
                comicModeEnabled = comicModeEnabled,
                readingDirection = readingDirection,
                onSuccess = onSuccess,
                onError = onError
            )
            return
        }

        val script = scriptForLanguage(languageCode)
        getRecognizer(script).process(inputImage)
            .addOnSuccessListener { visionText ->
                val rawBlocks = toRawOcrBlocks(visionText, script)
                val processed = ComicBubbleProcessor.processBlocks(
                    rawBlocks = rawBlocks,
                    sourceLang = languageCode,
                    comicModeEnabled = comicModeEnabled,
                    readingDirection = readingDirection
                )
                val resultList = processed.map { bubble ->
                    OcrBlock(
                        text = bubble.text,
                        boundingBox = bubble.box.toAndroidRect(),
                        orderIndex = bubble.orderIndex,
                        detectedLanguage = languageCode
                    )
                }
                onSuccess(resultList)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed for $languageCode", e)
                onError(e)
            }
    }

    private fun processAutoMultiScript(
        inputImage: InputImage,
        comicModeEnabled: Boolean,
        readingDirection: ReadingDirection,
        onSuccess: (List<OcrBlock>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val scripts = listOf(
            RecognizerScript.LATIN,
            RecognizerScript.JAPANESE,
            RecognizerScript.KOREAN,
            RecognizerScript.CHINESE
        )
        val tasks: List<Pair<RecognizerScript, Task<Text>>> = scripts.map { script ->
            script to getRecognizer(script).process(inputImage)
        }

        Tasks.whenAllComplete(tasks.map { it.second })
            .addOnCompleteListener {
                val allCandidates = mutableListOf<RawOcrBlock>()
                var firstFailure: Exception? = null
                var succeededCount = 0

                for ((script, task) in tasks) {
                    if (task.isSuccessful) {
                        succeededCount++
                        val visionText = task.result
                        if (visionText != null) {
                            allCandidates.addAll(toRawOcrBlocks(visionText, script))
                        }
                    } else {
                        val err = task.exception
                        if (err != null && firstFailure == null) {
                            firstFailure = err
                        }
                        Log.w(TAG, "Auto OCR script $script failed: ${err?.message}")
                    }
                }

                if (succeededCount == 0 && firstFailure != null) {
                    onError(firstFailure)
                    return@addOnCompleteListener
                }

                val fusedRaw = ComicBubbleProcessor.mergeCrossScriptCandidates(allCandidates)
                val processed = ComicBubbleProcessor.processBlocks(
                    rawBlocks = fusedRaw,
                    sourceLang = AppLanguages.AUTO,
                    comicModeEnabled = comicModeEnabled,
                    readingDirection = readingDirection
                )

                resolveBubbleLanguages(processed, onSuccess)
            }
    }

    private fun resolveBubbleLanguages(
        processed: List<ProcessedBubble>,
        onSuccess: (List<OcrBlock>) -> Unit
    ) {
        if (processed.isEmpty()) {
            onSuccess(emptyList())
            return
        }

        val needsLangIdIndices = processed.indices.filter { processed[it].detectedLang == null }
        if (needsLangIdIndices.isEmpty()) {
            onSuccess(
                processed.map { bubble ->
                    OcrBlock(
                        text = bubble.text,
                        boundingBox = bubble.box.toAndroidRect(),
                        orderIndex = bubble.orderIndex,
                        detectedLanguage = bubble.detectedLang
                    )
                }
            )
            return
        }

        val identifier = getLanguageIdentifier()
        val idTasks = needsLangIdIndices.associateWith { idx ->
            identifier.identifyLanguage(processed[idx].text)
        }

        Tasks.whenAllComplete(idTasks.values.toList())
            .addOnCompleteListener {
                val results = processed.mapIndexed { idx, bubble ->
                    val detected = bubble.detectedLang ?: idTasks[idx]?.let { task ->
                        if (task.isSuccessful) AppLanguages.normalizeDetectedTag(task.result) else null
                    }
                    OcrBlock(
                        text = bubble.text,
                        boundingBox = bubble.box.toAndroidRect(),
                        orderIndex = bubble.orderIndex,
                        detectedLanguage = detected
                    )
                }
                onSuccess(results)
            }
    }

    private fun toRawOcrBlocks(visionText: Text, script: RecognizerScript): List<RawOcrBlock> {
        return visionText.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val rawText = block.text.trim()
            if (rawText.isBlank()) return@mapNotNull null

            val lines = block.lines.mapNotNull inner@{ line ->
                val lineBox = line.boundingBox ?: return@inner null
                val lineText = line.text.trim()
                if (lineText.isBlank()) null else OcrLine(lineText, lineBox.toRectBox())
            }
            RawOcrBlock(
                text = rawText,
                box = box.toRectBox(),
                lines = lines,
                scriptHint = script
            )
        }
    }

    fun close() {
        recognizerCache.values.forEach { it.close() }
        recognizerCache.clear()
        languageIdentifier?.close()
        languageIdentifier = null
    }

    private fun Rect.toRectBox(): RectBox = RectBox(left, top, right, bottom)

    private fun RectBox.toAndroidRect(): Rect = Rect(left, top, right, bottom)
}
