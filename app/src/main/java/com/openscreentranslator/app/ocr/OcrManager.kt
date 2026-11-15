package com.openscreentranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val orderIndex: Int = 0
)

class OcrManager {
    private val TAG = "OcrManager"
    private val recognizerCache = mutableMapOf<String, TextRecognizer>()

    private fun getRecognizer(languageCode: String): TextRecognizer {
        return recognizerCache.getOrPut(languageCode) {
            when (languageCode) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
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
        val recognizer = getRecognizer(languageCode)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText: Text ->
                val rawBlocks = visionText.textBlocks.mapNotNull { block ->
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
                        lines = lines
                    )
                }

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
                        orderIndex = bubble.orderIndex
                    )
                }
                onSuccess(resultList)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed for $languageCode", e)
                onError(e)
            }
    }

    fun close() {
        recognizerCache.values.forEach { it.close() }
        recognizerCache.clear()
    }

    private fun Rect.toRectBox(): RectBox = RectBox(left, top, right, bottom)

    private fun RectBox.toAndroidRect(): Rect = Rect(left, top, right, bottom)
}
