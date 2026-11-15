package com.openscreentranslator.app.ocr

import com.openscreentranslator.app.data.AppLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun fusesOverlappingCrossScriptCandidatesAcrossMultipleLanguagesOnOnePage() {
        val enLat = RawOcrBlock(
            text = "Watch out!",
            box = RectBox(100, 100, 320, 160),
            scriptHint = RecognizerScript.LATIN
        )
        val enJaDup = RawOcrBlock(
            text = "Watch out!",
            box = RectBox(102, 101, 318, 159),
            scriptHint = RecognizerScript.JAPANESE
        )

        val jaNative = RawOcrBlock(
            text = "そんなまさか！",
            box = RectBox(550, 100, 820, 220),
            scriptHint = RecognizerScript.JAPANESE
        )
        val jaLatNoise = RawOcrBlock(
            text = "thA1!",
            box = RectBox(555, 105, 810, 215),
            scriptHint = RecognizerScript.LATIN
        )

        val koNative = RawOcrBlock(
            text = "잠깐만 기다려!",
            box = RectBox(100, 420, 380, 500),
            scriptHint = RecognizerScript.KOREAN
        )
        val koLatNoise = RawOcrBlock(
            text = "xAA=",
            box = RectBox(104, 422, 376, 496),
            scriptHint = RecognizerScript.LATIN
        )

        val fused = ComicBubbleProcessor.mergeCrossScriptCandidates(
            listOf(enJaDup, enLat, jaLatNoise, jaNative, koLatNoise, koNative)
        )

        assertEquals(3, fused.size)
        val texts = fused.map { it.text }.toSet()
        assertTrue(texts.contains("Watch out!"))
        assertTrue(texts.contains("そんなまさか！"))
        assertTrue(texts.contains("잠깐만 기다려!"))
        assertEquals(RecognizerScript.LATIN, fused.first { it.text == "Watch out!" }.scriptHint)
    }

    @Test
    fun processesMixedJaEnKoPageInAutoModeWithPerBubbleScriptSpacingAndDetection() {
        val jaVertColRight = RawOcrBlock(
            text = "お前は",
            box = RectBox(700, 100, 740, 280),
            lines = listOf(OcrLine("お前は", RectBox(700, 100, 740, 280))),
            scriptHint = RecognizerScript.JAPANESE
        )
        val jaVertColLeft = RawOcrBlock(
            text = "誰だ？",
            box = RectBox(650, 105, 690, 280),
            lines = listOf(OcrLine("誰だ？", RectBox(650, 105, 690, 280))),
            scriptHint = RecognizerScript.JAPANESE
        )
        val enWrapped = RawOcrBlock(
            text = "Stay right\nthere now!",
            box = RectBox(100, 110, 320, 190),
            lines = listOf(
                OcrLine("Stay right", RectBox(100, 110, 320, 145)),
                OcrLine("there now!", RectBox(105, 152, 315, 188))
            ),
            scriptHint = RecognizerScript.LATIN
        )
        val koWrapped = RawOcrBlock(
            text = "잠깐만\n기다려!",
            box = RectBox(120, 420, 340, 510),
            lines = listOf(
                OcrLine("잠깐만", RectBox(120, 420, 340, 455)),
                OcrLine("기다려!", RectBox(120, 465, 340, 505))
            ),
            scriptHint = RecognizerScript.KOREAN
        )
        val kanjiOnly = RawOcrBlock(
            text = "無言",
            box = RectBox(640, 430, 720, 500),
            scriptHint = RecognizerScript.JAPANESE
        )

        val result = ComicBubbleProcessor.processBlocks(
            rawBlocks = listOf(enWrapped, koWrapped, jaVertColLeft, jaVertColRight, kanjiOnly),
            sourceLang = AppLanguages.AUTO,
            comicModeEnabled = true,
            readingDirection = ReadingDirection.MANGA_RTL
        )

        assertEquals(4, result.size)
        // RTL top tier: JA bubble -> EN bubble; bottom tier: Kanji-only JA -> KO
        assertEquals("お前は誰だ？", result[0].text)
        assertEquals("ja", result[0].detectedLang)

        assertEquals("Stay right there now!", result[1].text)
        assertNull(result[1].detectedLang) // Deferred to ML Kit LanguageIdentification

        assertEquals("無言", result[2].text)
        assertEquals("ja", result[2].detectedLang) // Kanji-only on Kana page resolves to Japanese

        assertEquals("잠깐만 기다려!", result[3].text) // Hangul preserves spaces between lines
        assertEquals("ko", result[3].detectedLang)
    }
}
