package com.openscreentranslator.app.translation

import org.junit.Assert.assertEquals
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
}
