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
    val boundingBox: Rect
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
        sourceLanguage: String,
        onSuccess: (List<OcrBlock>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = getRecognizer(sourceLanguage)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText: Text ->
                val resultList = mutableListOf<OcrBlock>()
                for (block in visionText.textBlocks) {
                    val text = block.text.trim()
                    val box = block.boundingBox
                    if (text.isNotBlank() && box != null) {
                        resultList.add(OcrBlock(text, box))
                    }
                }
                Log.d(TAG, "OCR detected ${resultList.size} text blocks")
                onSuccess(resultList)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "OCR recognition failed", exception)
                onError(exception)
            }
    }

    fun close() {
        recognizerCache.values.forEach { it.close() }
        recognizerCache.clear()
    }
}
