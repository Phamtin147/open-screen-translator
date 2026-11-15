package com.openscreentranslator.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HymtPromptAndNoiseFilterTest {

    @Test
    fun normalizesAllCapsEnglishComicBubblesAndLineBreaks() {
        val raw = "I WON'T LET\nYOU ESCAPE\nTHIS TIME!"
        val normalized = TranslationBatchHelper.normalizeComicTextForHymt(raw)
        assertEquals("I won't let you escape this time!", normalized)

        val rawTwo = "STOP RIGHT\nTHERE, YOU\nFOOL!!"
        assertEquals("Stop right there, you fool!!", TranslationBatchHelper.normalizeComicTextForHymt(rawTwo))
    }

    @Test
    fun joinsBrokenCjkLinesWithoutInsertingSpaces() {
        val jaRaw = "なんだと！？\nまさか、あいつが\n生きていたのか！？"
        assertEquals("なんだと！？まさか、あいつが生きていたのか！？", TranslationBatchHelper.normalizeComicTextForHymt(jaRaw))
    }

    @Test
    fun buildsOfficialHymtSingleSegmentPrompt() {
        val prompt = TranslationBatchHelper.buildSingleHymtPrompt("STOP RIGHT\nTHERE!!", "vi")
        assertTrue(prompt.startsWith("Translate the following segment into Vietnamese, without additional explanation.\n\n"))
        assertTrue(prompt.endsWith("Stop right there!!"))
        assertFalse(prompt.contains("[[B1]]"))
    }

    @Test
    fun skipsNonDialogueStatusAndUrlArtifacts() {
        assertTrue(TranslationBatchHelper.shouldSkipTranslation("9:48"))
        assertTrue(TranslationBatchHelper.shouldSkipTranslation("9:48 3 M"))
        assertTrue(TranslationBatchHelper.shouldSkipTranslation("D"))
        assertTrue(TranslationBatchHelper.shouldSkipTranslation("https://example.com/chap-1"))
        assertTrue(TranslationBatchHelper.shouldSkipTranslation("O 127.0.0.1:18765/comic_test_en.html"))
        assertTrue(TranslationBatchHelper.shouldSkipTranslation("100%"))

        assertFalse(TranslationBatchHelper.shouldSkipTranslation("死"))
        assertFalse(TranslationBatchHelper.shouldSkipTranslation("Stop right there!"))
        assertFalse(TranslationBatchHelper.shouldSkipTranslation("おい、待てよ！"))
    }

    @Test
    fun stripsRedundantWrappingQuotesOnlyWhenSourceWasUnquoted() {
        assertEquals(
            "Đừng ồn nữa!",
            TranslationBatchHelper.stripRedundantOuterQuotes("うるさい！", "“Đừng ồn nữa!”")
        )
        assertEquals(
            "\"Hello!\"",
            TranslationBatchHelper.stripRedundantOuterQuotes("\"Xin chào!\"", "\"Hello!\"")
        )
    }
}
