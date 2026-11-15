package com.openscreentranslator.app.translation

import com.openscreentranslator.app.data.AppLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchHelperTest {

    @Test
    fun parsesTaggedComicBatchTranslationsAccurately() {
        val raw = """
            <｜hy_begin▁of▁sentence｜>[[B1]] Ngươi là ai vậy?!
            [[B2]] Đừng có lại gần đây!
            [B3] Bình tĩnh đi, tôi không phải kẻ thù.
        """.trimIndent()

        val parsed = TranslationBatchHelper.parseTaggedBatchResponse(raw, expectedCount = 3)
        assertEquals(3, parsed.size)
        assertEquals("Ngươi là ai vậy?!", parsed[1])
        assertEquals("Đừng có lại gần đây!", parsed[2])
        assertEquals("Bình tĩnh đi, tôi không phải kẻ thù.", parsed[3])
    }

    @Test
    fun stripsSpecialTokensAndMarkdownFencesFromSingleOutput() {
        val cleaned = TranslationBatchHelper.cleanSingleOutput("```[B1] Không đời nào!```<｜hy_place▁holder▁no▁2｜>")
        assertEquals("Không đời nào!", cleaned)
    }

    @Test
    fun buildsMultiLanguageBatchPromptWhenAutoModeDetectsMultipleLanguages() {
        val prompt = TranslationBatchHelper.buildTaggedBatchPrompt(
            bubbles = listOf("そんなまさか！", "Watch out!", "잠깐만!"),
            targetLang = "vi",
            sourceLang = AppLanguages.AUTO,
            includeContextInstruction = true,
            bubbleDetectedLangs = listOf("ja", "en", "ko")
        )

        assertTrue(prompt.contains("Japanese"))
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("Korean"))
        assertTrue(prompt.contains("multiple source languages"))
        assertTrue(prompt.contains("Vietnamese"))
        assertTrue(prompt.contains("[[B1]] そんなまさか！"))
        assertTrue(prompt.contains("[[B2]] Watch out!"))
        assertTrue(prompt.contains("[[B3]] 잠깐만!"))
    }

    @Test
    fun buildsGeminiSinglePromptForAutoAndSpecificSource() {
        val autoPrompt = TranslationBatchHelper.buildGeminiSinglePrompt(
            sourceText = "C'est impossible",
            sourceLang = AppLanguages.AUTO,
            targetLang = "vi"
        )
        assertTrue(autoPrompt.contains("Automatically detect the source language"))

        val explicitPrompt = TranslationBatchHelper.buildGeminiSinglePrompt(
            sourceText = "Bonjour",
            sourceLang = "fr",
            targetLang = "vi"
        )
        assertTrue(explicitPrompt.contains("Source language: French."))
    }

    @Test
    fun normalizesDetectedBcp47TagsSafely() {
        assertEquals("zh", AppLanguages.normalizeDetectedTag("zh-Hant"))
        assertEquals("pt", AppLanguages.normalizeDetectedTag("pt-BR"))
        assertEquals("ja", AppLanguages.normalizeDetectedTag("ja"))
        assertNull(AppLanguages.normalizeDetectedTag("und"))
        assertNull(AppLanguages.normalizeDetectedTag("ja-Latn"))
        assertEquals("French", AppLanguages.englishName("fr"))
        assertEquals("Russian", AppLanguages.englishName("ru"))
    }
}
