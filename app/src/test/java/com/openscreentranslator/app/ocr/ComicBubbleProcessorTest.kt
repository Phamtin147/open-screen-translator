package com.openscreentranslator.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicBubbleProcessorTest {

    @Test
    fun joinsMultiLineWrappedEnglishSentenceWithoutBreakingHyphens() {
        val raw = RawOcrBlock(
            text = "This is abso-\nlutely un-\nbelievable!\nWait here.",
            box = RectBox(100, 100, 340, 260),
            lines = listOf(
                OcrLine("This is abso-", RectBox(100, 100, 340, 130)),
                OcrLine("lutely un-", RectBox(100, 140, 300, 170)),
                OcrLine("believable!", RectBox(100, 180, 290, 210)),
                OcrLine("Wait here.", RectBox(120, 220, 280, 250))
            )
        )

        val result = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(raw),
            sourceLang = "en",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.COMIC_LTR
        )

        assertEquals(1, result.size)
        assertEquals("This is absolutely unbelievable! Wait here.", result[0].text)
    }

    @Test
    fun joinsJapaneseWrappedLinesWithoutSpuriousSpaces() {
        val raw = RawOcrBlock(
            text = "そんな\nまさか\n嘘だろ！",
            box = RectBox(600, 120, 740, 360),
            lines = listOf(
                OcrLine("そんな", RectBox(680, 120, 730, 300)),
                OcrLine("まさか", RectBox(635, 130, 680, 320)),
                OcrLine("嘘だろ！", RectBox(595, 130, 635, 350))
            )
        )

        val result = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(raw),
            sourceLang = "ja",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.MANGA_RTL
        )

        assertEquals(1, result.size)
        assertEquals("そんなまさか嘘だろ！", result[0].text)
    }

    @Test
    fun mergesSplitBlocksInsideSingleHorizontalSpeechBubble() {
        val topHalf = RawOcrBlock(
            text = "Why didn't you",
            box = RectBox(120, 200, 340, 240),
            lines = listOf(OcrLine("Why didn't you", RectBox(120, 200, 340, 240)))
        )
        val bottomHalf = RawOcrBlock(
            text = "tell me sooner?",
            box = RectBox(135, 254, 325, 294),
            lines = listOf(OcrLine("tell me sooner?", RectBox(135, 254, 325, 294)))
        )
        val distantBubble = RawOcrBlock(
            text = "Stop right there!",
            box = RectBox(620, 200, 860, 260),
            lines = listOf(OcrLine("Stop right there!", RectBox(620, 200, 860, 260)))
        )

        val result = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(topHalf, bottomHalf, distantBubble),
            sourceLang = "en",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.COMIC_LTR
        )

        assertEquals(2, result.size)
        assertEquals("Why didn't you tell me sooner?", result[0].text)
        assertEquals("Stop right there!", result[1].text)
        assertTrue(result[0].mergedCount == 2)
    }

    @Test
    fun sortsMangaTwoColumnPageRightToLeftViaRecursiveXyCut() {
        // Classic manga layout: Right half has TWO stacked panels, Left half has ONE tall panel
        val rightTop = RawOcrBlock("First right top", RectBox(580, 100, 880, 200))
        val rightBottom = RawOcrBlock("Second right bottom", RectBox(580, 500, 880, 620))
        val leftMid = RawOcrBlock("Third left panel", RectBox(100, 250, 420, 420))

        val rtl = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(leftMid, rightBottom, rightTop),
            sourceLang = "en",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.MANGA_RTL
        )

        assertEquals(
            listOf("First right top", "Second right bottom", "Third left panel"),
            rtl.map { it.text }
        )

        val ltr = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(rightBottom, leftMid, rightTop),
            sourceLang = "en",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.COMIC_LTR
        )

        assertEquals(
            listOf("Third left panel", "First right top", "Second right bottom"),
            ltr.map { it.text }
        )
    }

    @Test
    fun filtersNoiseSymbolsAndMergesVerticalMangaColumnsRightToLeft() {
        val noise = RawOcrBlock("|", RectBox(5, 5, 10, 40))
        val colRight = RawOcrBlock(
            text = "お前は",
            box = RectBox(700, 100, 740, 280),
            lines = listOf(OcrLine("お前は", RectBox(700, 100, 740, 280)))
        )
        val colLeft = RawOcrBlock(
            text = "誰だ？",
            box = RectBox(650, 110, 690, 280),
            lines = listOf(OcrLine("誰だ？", RectBox(650, 110, 690, 280)))
        )

        val result = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(noise, colLeft, colRight),
            sourceLang = "ja",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.MANGA_RTL
        )

        assertEquals(1, result.size)
        assertEquals("お前は誰だ？", result[0].text)
    }

    @Test
    fun sortsTwoByTwoComicGridInProperRowOrder() {
        val tr = RawOcrBlock("TR", RectBox(600, 100, 900, 220))
        val tl = RawOcrBlock("TL", RectBox(100, 110, 400, 230))
        val br = RawOcrBlock("BR", RectBox(600, 500, 900, 620))
        val bl = RawOcrBlock("BL", RectBox(100, 510, 400, 630))

        val rtl = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(bl, br, tl, tr),
            sourceLang = "en",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.MANGA_RTL
        )
        assertEquals(listOf("TR", "TL", "BR", "BL"), rtl.map { it.text })

        val ltr = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(bl, br, tl, tr),
            sourceLang = "en",
            comicModeEnabled = true,
            readingDirection = ReadingDirection.COMIC_LTR
        )
        assertEquals(listOf("TL", "TR", "BL", "BR"), ltr.map { it.text })
    }
}
