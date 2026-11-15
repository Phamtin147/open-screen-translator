package com.openscreentranslator.app.ocr

import com.openscreentranslator.app.data.AppLanguages
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RecognizerScript {
    LATIN,
    JAPANESE,
    KOREAN,
    CHINESE
}

/**
 * Immutable bounding box in screen pixel coordinates.
 * Pure Kotlin so spatial comic logic can be tested cleanly on JVM without android.graphics stubs.
 */
data class RectBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val area: Float get() = width.toFloat() * height.toFloat()
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun union(other: RectBox): RectBox = RectBox(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom)
    )

    fun intersectionArea(other: RectBox): Float {
        val iLeft = max(left, other.left)
        val iTop = max(top, other.top)
        val iRight = min(right, other.right)
        val iBottom = min(bottom, other.bottom)
        if (iRight <= iLeft || iBottom <= iTop) return 0f
        return (iRight - iLeft).toFloat() * (iBottom - iTop).toFloat()
    }
}

data class OcrLine(
    val text: String,
    val box: RectBox
)

data class RawOcrBlock(
    val text: String,
    val box: RectBox,
    val lines: List<OcrLine> = emptyList(),
    val scriptHint: RecognizerScript = RecognizerScript.LATIN
)

data class ProcessedBubble(
    val orderIndex: Int,
    val text: String,
    val box: RectBox,
    val mergedCount: Int,
    val detectedLang: String? = null
)

enum class ReadingDirection {
    MANGA_RTL,
    COMIC_LTR
}

/**
 * Solves two core comic/manga OCR issues:
 * 1. Wrapped multi-line text inside speech bubbles (breaks hyphenation, mid-sentence newlines, vertical CJK line splits).
 * 2. Multiple fragmented OCR blocks inside the same bubble + naive y-order tearing multi-panel comic pages apart.
 * 3. Cross-script candidate fusion when running Latin + Japanese + Korean + Chinese OCR simultaneously.
 */
object ComicBubbleProcessor {

    private val PURE_NOISE_REGEX = Regex("^[|_~—\\-=*·•.,:;`'\"\\\\/\\[\\](){}<>]+$")
    private val HYPHEN_BREAK_REGEX = Regex("([\\p{L}]{2,})-\\s*\\n\\s*([\\p{L}]{2,})")
    private val CJK_CHAR_REGEX = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")
    private val SENTENCE_END_CHARS = setOf('.', '!', '?', '…', '。', '！', '？', '」', '』', '"', '”')

    /**
     * Fuses OCR detections emitted across Latin, Japanese, Korean, and Chinese recognizers on one image.
     * Overlapping candidates covering the same speech bubble keep whichever recognizer best matches
     * the native Unicode script (preventing duplicate cards and Latin garbling of CJK/Hangul glyphs).
     */
    fun mergeCrossScriptCandidates(candidates: List<RawOcrBlock>): List<RawOcrBlock> {
        if (candidates.isEmpty()) return emptyList()

        val validCandidates = candidates.filter { cand ->
            val trimmed = cand.text.trim()
            trimmed.isNotEmpty() && !PURE_NOISE_REGEX.matches(trimmed) && cand.box.width >= 6 && cand.box.height >= 6
        }
        if (validCandidates.isEmpty()) return emptyList()

        val pageHasKana = validCandidates.any { AppLanguages.containsKana(it.text) }

        val ranked = validCandidates
            .mapIndexed { idx, block ->
                val score = scoreScriptCandidate(block, pageHasKana)
                Triple(score, idx, block)
            }
            .sortedWith(compareByDescending<Triple<Float, Int, RawOcrBlock>> { it.first }.thenBy { it.second })

        val accepted = mutableListOf<RawOcrBlock>()
        for ((_, _, candidate) in ranked) {
            val overlapsExisting = accepted.any { existing ->
                val inter = existing.box.intersectionArea(candidate.box)
                if (inter <= 0f) {
                    false
                } else {
                    val minArea = min(existing.box.area, candidate.box.area).coerceAtLeast(1f)
                    val unionArea = (existing.box.area + candidate.box.area - inter).coerceAtLeast(1f)
                    val containment = inter / minArea
                    val iou = inter / unionArea
                    iou >= 0.30f || containment >= 0.50f
                }
            }
            if (!overlapsExisting) {
                accepted.add(candidate)
            }
        }
        return accepted
    }

    internal fun scoreScriptCandidate(block: RawOcrBlock, pageHasKana: Boolean): Float {
        var kanaCount = 0
        var hangulCount = 0
        var hanCount = 0
        var latinCount = 0
        var digitCount = 0
        var noiseCount = 0

        for (ch in block.text) {
            when {
                ch.isWhitespace() -> Unit
                AppLanguages.isKanaChar(ch) -> kanaCount++
                AppLanguages.isHangulChar(ch) -> hangulCount++
                AppLanguages.isHanChar(ch) -> hanCount++
                ch.isLetter() -> latinCount++ // Covers ASCII + extended Latin (Vietnamese, French, Spanish, ...)
                ch.isDigit() -> digitCount++
                ch in setOf('.', ',', '!', '?', '…', '。', '！', '？', '-', '\'', '"', ':', '~') -> Unit
                else -> noiseCount++
            }
        }

        val contentScore = kanaCount * 4.5f +
            hangulCount * 4.2f +
            hanCount * 3.2f +
            latinCount * 2.5f +
            digitCount * 0.9f -
            noiseCount * 1.1f

        val nativeAffinity = when (block.scriptHint) {
            RecognizerScript.JAPANESE -> when {
                kanaCount > 0 -> 12.0f
                hanCount > 0 && pageHasKana -> 10.0f
                hanCount > 0 -> 7.0f
                else -> 0f
            }
            RecognizerScript.KOREAN -> if (hangulCount > 0) 12.0f else 0f
            RecognizerScript.CHINESE -> when {
                hanCount > 0 && kanaCount == 0 -> 9.0f
                hanCount > 0 -> 4.0f
                else -> 0f
            }
            RecognizerScript.LATIN -> {
                if (latinCount > 0 && kanaCount == 0 && hangulCount == 0 && hanCount == 0) 6.5f else 0f
            }
        }

        return contentScore + nativeAffinity
    }

    fun processBlocks(
        rawBlocks: List<RawOcrBlock>,
        sourceLang: String,
        comicModeEnabled: Boolean,
        readingDirection: ReadingDirection
    ): List<ProcessedBubble> {
        val pageHasKana = rawBlocks.any { AppLanguages.containsKana(it.text) }

        val validBlocks = rawBlocks.mapNotNull { block ->
            val cleanedLines = extractCleanLines(block)
            val joined = if (cleanedLines.isNotEmpty()) {
                joinLinesNaturally(cleanedLines, sourceLang)
            } else {
                normalizePlainText(block.text, sourceLang)
            }
            if (!isMeaningfulText(joined, block.box, comicModeEnabled)) {
                null
            } else {
                InternalCluster(
                    box = block.box,
                    lines = if (cleanedLines.isNotEmpty()) cleanedLines else listOf(OcrLine(joined, block.box)),
                    estimatedLineHeight = estimateLineHeight(block, cleanedLines),
                    isVerticalCjk = isVerticalCjkBlock(block, cleanedLines, sourceLang),
                    blockCount = 1
                )
            }
        }

        if (validBlocks.isEmpty()) return emptyList()

        val clustered = if (comicModeEnabled) {
            mergeSpeechBubbleClusters(validBlocks, sourceLang)
        } else {
            validBlocks
        }

        val ordered = if (comicModeEnabled) {
            sortComicReadingOrder(clustered, readingDirection)
        } else {
            clustered.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        }

        return ordered.mapIndexed { index, cluster ->
            val mergedText = assembleClusterText(cluster, sourceLang)
            val detected = if (sourceLang == AppLanguages.AUTO) {
                AppLanguages.detectDeterministicScriptLanguage(mergedText, pageHasKana)
            } else {
                sourceLang
            }
            ProcessedBubble(
                orderIndex = index + 1,
                text = mergedText,
                box = cluster.box,
                mergedCount = cluster.blockCount,
                detectedLang = detected
            )
        }.filter { it.text.isNotBlank() }
    }

    internal fun normalizePlainText(raw: String, sourceLang: String): String {
        val unified = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (unified.isEmpty()) return ""

        val dehyphenated = HYPHEN_BREAK_REGEX.replace(unified) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}"
        }

        val lines = dehyphenated.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !isStrayArtifactLine(it) }

        return joinLineStrings(lines, sourceLang)
    }

    private fun extractCleanLines(block: RawOcrBlock): List<OcrLine> {
        if (block.lines.isEmpty()) return emptyList()
        return block.lines.mapNotNull { line ->
            val clean = line.text.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty() || isStrayArtifactLine(clean)) null else OcrLine(clean, line.box)
        }
    }

    private fun joinLinesNaturally(lines: List<OcrLine>, sourceLang: String): String {
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0].text

        // For vertical CJK columns inside one TextBlock, ML Kit may emit columns top-to-bottom or side-by-side
        val strings = lines.map { it.text }
        return joinLineStrings(strings, sourceLang)
    }

    private fun joinLineStrings(lines: List<String>, sourceLang: String): String {
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0]

        val sb = StringBuilder()
        for (i in lines.indices) {
            val current = lines[i].trim()
            if (current.isEmpty()) continue

            if (sb.isEmpty()) {
                sb.append(current)
                continue
            }

            // De-hyphenate Latin word break between consecutive lines
            val prevStr = sb.toString()
            if (prevStr.length >= 3 &&
                prevStr.endsWith("-") &&
                prevStr[prevStr.length - 2].isLetter() &&
                current[0].isLetter() &&
                current[0].isLowerCase()
            ) {
                sb.deleteCharAt(sb.length - 1)
                sb.append(current)
                continue
            }

            val lastChar = prevStr.last()
            val cjkContext = isCjkLinePair(prevStr, current, sourceLang)
            if (cjkContext) {
                // CJK has no spaces between words: joining without space fixes wrapped phrasing.
                // Add a space only after sentence-closing punctuation for clarity.
                if (lastChar in SENTENCE_END_CHARS && lastChar != '…') {
                    sb.append(' ').append(current)
                } else {
                    sb.append(current)
                }
            } else {
                sb.append(' ').append(current)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun isCjkLinePair(prev: String, next: String, sourceLang: String): Boolean {
        if (sourceLang == "ja" || sourceLang == "zh") {
            return CJK_CHAR_REGEX.containsMatchIn(prev) || CJK_CHAR_REGEX.containsMatchIn(next)
        }
        if (sourceLang == AppLanguages.AUTO) {
            val combinedHasCjk = CJK_CHAR_REGEX.containsMatchIn(prev) || CJK_CHAR_REGEX.containsMatchIn(next)
            if (!combinedHasCjk) return false
            // Korean Hangul uses spaces between words
            if (AppLanguages.containsHangul(prev) || AppLanguages.containsHangul(next)) return false
            val prevLast = prev.lastOrNull() ?: return false
            val nextFirst = next.firstOrNull() ?: return false
            // Keep spaces between two adjacent ASCII words inside mixed-script Japanese text
            val bothAsciiAlphaNum = prevLast.code < 128 && prevLast.isLetterOrDigit() &&
                nextFirst.code < 128 && nextFirst.isLetterOrDigit()
            return !bothAsciiAlphaNum
        }
        val prevLast = prev.lastOrNull() ?: return false
        val nextFirst = next.firstOrNull() ?: return false
        return CJK_CHAR_REGEX.matches(prevLast.toString()) && CJK_CHAR_REGEX.matches(nextFirst.toString())
    }

    private fun isStrayArtifactLine(text: String): Boolean {
        if (text.length <= 2 && PURE_NOISE_REGEX.matches(text)) return true
        return false
    }

    private fun isMeaningfulText(text: String, box: RectBox, comicModeEnabled: Boolean): Boolean {
        if (text.isBlank()) return false
        if (PURE_NOISE_REGEX.matches(text)) return false
        if (comicModeEnabled && (box.width < 8 || box.height < 8)) return false
        // Filter single non-alphanumeric symbol or stray page-edge digit noise smaller than tiny text
        if (text.length == 1 && !text[0].isLetterOrDigit()) return false
        return true
    }

    private fun estimateLineHeight(block: RawOcrBlock, lines: List<OcrLine>): Float {
        if (lines.isNotEmpty()) {
            val heights = lines.map { line ->
                // Vertical Japanese column line has width narrower than height
                if (line.box.height > line.box.width * 1.8f) {
                    line.box.width.toFloat()
                } else {
                    line.box.height.toFloat()
                }
            }.sorted()
            return heights[heights.size / 2].coerceAtLeast(10f)
        }
        val rawLineCount = block.text.lines().count { it.isNotBlank() }.coerceAtLeast(1)
        return (block.box.height.toFloat() / rawLineCount).coerceAtLeast(10f)
    }

    private fun isVerticalCjkBlock(
        block: RawOcrBlock,
        lines: List<OcrLine>,
        sourceLang: String
    ): Boolean {
        if (sourceLang != AppLanguages.AUTO && sourceLang != "ja" && sourceLang != "zh") return false
        if (!CJK_CHAR_REGEX.containsMatchIn(block.text)) return false
        if (lines.any { it.box.height > it.box.width * 1.75f }) return true
        return lines.size == 1 && block.box.height > block.box.width * 1.75f
    }

    private data class InternalCluster(
        val box: RectBox,
        val lines: List<OcrLine>,
        val estimatedLineHeight: Float,
        val isVerticalCjk: Boolean,
        val blockCount: Int
    )

    /**
     * Merges OCR TextBlocks that belong to the same speech bubble without swallowing distinct bubbles.
     */
    private fun mergeSpeechBubbleClusters(
        items: List<InternalCluster>,
        sourceLang: String
    ): List<InternalCluster> {
        val clusters = items.toMutableList()
        var mergedAny: Boolean
        do {
            mergedAny = false
            outer@ for (i in 0 until clusters.size) {
                for (j in i + 1 until clusters.size) {
                    val a = clusters[i]
                    val b = clusters[j]
                    if (shouldMergeSameBubble(a, b, sourceLang)) {
                        clusters[i] = InternalCluster(
                            box = a.box.union(b.box),
                            lines = a.lines + b.lines,
                            estimatedLineHeight = (a.estimatedLineHeight + b.estimatedLineHeight) * 0.5f,
                            isVerticalCjk = a.isVerticalCjk || b.isVerticalCjk,
                            blockCount = a.blockCount + b.blockCount
                        )
                        clusters.removeAt(j)
                        mergedAny = true
                        break@outer
                    }
                }
            }
        } while (mergedAny)

        return clusters
    }

    private fun hasConflictingNativeScripts(a: InternalCluster, b: InternalCluster): Boolean {
        val textA = a.lines.joinToString("") { it.text }
        val textB = b.lines.joinToString("") { it.text }
        val aKana = AppLanguages.containsKana(textA)
        val bKana = AppLanguages.containsKana(textB)
        val aHangul = AppLanguages.containsHangul(textA)
        val bHangul = AppLanguages.containsHangul(textB)
        return (aKana && bHangul) || (aHangul && bKana)
    }

    private fun shouldMergeSameBubble(
        a: InternalCluster,
        b: InternalCluster,
        sourceLang: String
    ): Boolean {
        if (sourceLang == AppLanguages.AUTO && hasConflictingNativeScripts(a, b)) return false

        val lhA = a.estimatedLineHeight
        val lhB = b.estimatedLineHeight
        val minLh = min(lhA, lhB).coerceAtLeast(8f)
        val maxLh = max(lhA, lhB)

        // Do not merge drastically different font scales (e.g. dialogue + giant SFX / title)
        if (maxLh / minLh > 1.85f) return false

        val refUnit = maxLh

        // Case 1: Vertical CJK columns inside one manga speech bubble sit side-by-side
        if ((a.isVerticalCjk || b.isVerticalCjk) && (sourceLang == AppLanguages.AUTO || sourceLang == "ja" || sourceLang == "zh")) {
            val yOverlap = min(a.box.bottom, b.box.bottom) - max(a.box.top, b.box.top)
            val minHeight = min(a.box.height, b.box.height).coerceAtLeast(1)
            val xGap = horizontalGap(a.box, b.box)
            if (yOverlap >= 0.30f * minHeight && xGap <= refUnit * 1.15f) {
                return true
            }
        }

        // Case 2: Horizontal text stacked vertically inside one oval speech bubble
        val yGap = verticalGap(a.box, b.box)
        if (yGap <= refUnit * 1.05f && yGap >= -refUnit * 0.45f) {
            val xOverlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
            val minWidth = min(a.box.width, b.box.width).coerceAtLeast(1)
            val maxWidth = max(a.box.width, b.box.width).coerceAtLeast(1)
            val centerDx = abs(a.box.centerX - b.box.centerX)
            val sameColumnWidth = maxWidth.toFloat() / minWidth.toFloat() <= 3.2f

            // Speech bubble lines either overlap noticeably horizontally or share nearly the same centered X axis
            if ((xOverlap >= 0.35f * minWidth || centerDx <= max(refUnit * 1.8f, maxWidth * 0.35f)) &&
                horizontalGap(a.box, b.box) <= refUnit * 0.6f &&
                sameColumnWidth
            ) {
                return true
            }
        }

        return false
    }

    private fun verticalGap(a: RectBox, b: RectBox): Int {
        return max(a.top, b.top) - min(a.bottom, b.bottom)
    }

    private fun horizontalGap(a: RectBox, b: RectBox): Int {
        return max(a.left, b.left) - min(a.right, b.right)
    }

    private fun assembleClusterText(cluster: InternalCluster, sourceLang: String): String {
        if (cluster.blockCount == 1 && cluster.lines.size == 1) {
            return normalizePlainText(cluster.lines[0].text, sourceLang)
        }

        val orderedLines = if (cluster.isVerticalCjk) {
            // Vertical CJK: sort columns Right-to-Left, breaking ties Top-to-Bottom
            cluster.lines.sortedWith(compareBy<OcrLine> { -it.box.centerX }.thenBy { it.box.top })
        } else {
            // Horizontal text: sort Top-to-Bottom, Left-to-Right within same line baseline
            val tol = (cluster.estimatedLineHeight * 0.45f).toInt().coerceAtLeast(6)
            cluster.lines.sortedWith { l1, l2 ->
                if (abs(l1.box.centerY - l2.box.centerY) <= tol) {
                    l1.box.left.compareTo(l2.box.left)
                } else {
                    l1.box.centerY.compareTo(l2.box.centerY)
                }
            }
        }

        return joinLineStrings(orderedLines.map { it.text }, sourceLang)
    }

    /**
     * Recursive XY-Cut comic panel/bubble reading order algorithm:
     * Handles L-shaped multi-tier comic pages far more accurately than flat y-then-x sorting.
     */
    private fun sortComicReadingOrder(
        clusters: List<InternalCluster>,
        direction: ReadingDirection
    ): List<InternalCluster> {
        if (clusters.size <= 1) return clusters

        val ySorted = clusters.sortedBy { it.box.top }
        val hPartitions = partitionByHorizontalGutter(ySorted)

        val xOrdered = when (direction) {
            ReadingDirection.MANGA_RTL -> clusters.sortedByDescending { it.box.right }
            ReadingDirection.COMIC_LTR -> clusters.sortedBy { it.box.left }
        }
        val vPartitions = partitionByVerticalGutter(xOrdered, direction)

        // When both horizontal and vertical gutters exist (e.g. tall left column beside 2 stacked
        // right panels), prefer vertical column split whenever horizontal strips alternate sides.
        if (vPartitions.size > 1 && hPartitions.size > 1 && alternatesAcrossColumns(hPartitions)) {
            return vPartitions.flatMap { sortComicReadingOrder(it, direction) }
        }

        if (hPartitions.size > 1) {
            return hPartitions.flatMap { sortComicReadingOrder(it, direction) }
        }

        if (vPartitions.size > 1) {
            return vPartitions.flatMap { sortComicReadingOrder(it, direction) }
        }

        // Base case (overlapping bubbles inside one panel or tight cluster):
        // Group into horizontal tiers by vertical overlap; inside a tier order RTL or LTR
        return clusters.sortedWith { a, b ->
            val yOverlap = min(a.box.bottom, b.box.bottom) - max(a.box.top, b.box.top)
            val minH = min(a.box.height, b.box.height).coerceAtLeast(1)
            val sameTier = yOverlap >= 0.35f * minH || abs(a.box.centerY - b.box.centerY) <= minH * 0.4f
            if (sameTier) {
                when (direction) {
                    ReadingDirection.MANGA_RTL -> b.box.centerX.compareTo(a.box.centerX)
                    ReadingDirection.COMIC_LTR -> a.box.centerX.compareTo(b.box.centerX)
                }
            } else {
                a.box.centerY.compareTo(b.box.centerY)
            }
        }
    }

    private fun alternatesAcrossColumns(
        hPartitions: List<List<InternalCluster>>
    ): Boolean {
        // Compute page horizontal midpoint across clusters
        val all = hPartitions.flatten()
        val minX = all.minOf { it.box.left }
        val maxX = all.maxOf { it.box.right }
        val midX = (minX + maxX) * 0.5f

        val stripSides = hPartitions.map { strip ->
            val hasLeft = strip.any { it.box.centerX < midX }
            val hasRight = strip.any { it.box.centerX >= midX }
            when {
                hasLeft && hasRight -> 0
                hasRight -> 1
                else -> -1
            }
        }

        // If any strip spans both sides (e.g. 2x2 grid row with TR+TL), horizontal strips should lead
        if (stripSides.any { it == 0 }) return false

        // Strips live strictly on one side each and flip sides between strips (e.g. Right -> Left -> Right)
        for (i in 1 until stripSides.size) {
            if (stripSides[i] != stripSides[i - 1]) {
                return true
            }
        }
        return false
    }

    private fun partitionByHorizontalGutter(ySorted: List<InternalCluster>): List<List<InternalCluster>> {
        val groups = mutableListOf<List<InternalCluster>>()
        var currentGroup = mutableListOf(ySorted.first())
        var maxBottom = ySorted.first().box.bottom

        for (i in 1 until ySorted.size) {
            val item = ySorted[i]
            if (item.box.top >= maxBottom) {
                groups.add(currentGroup)
                currentGroup = mutableListOf(item)
                maxBottom = item.box.bottom
            } else {
                currentGroup.add(item)
                maxBottom = max(maxBottom, item.box.bottom)
            }
        }
        groups.add(currentGroup)
        return groups
    }

    private fun partitionByVerticalGutter(
        xOrdered: List<InternalCluster>,
        direction: ReadingDirection
    ): List<List<InternalCluster>> {
        val groups = mutableListOf<List<InternalCluster>>()
        var currentGroup = mutableListOf(xOrdered.first())

        if (direction == ReadingDirection.MANGA_RTL) {
            var minLeft = xOrdered.first().box.left
            for (i in 1 until xOrdered.size) {
                val item = xOrdered[i]
                if (item.box.right <= minLeft) {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf(item)
                    minLeft = item.box.left
                } else {
                    currentGroup.add(item)
                    minLeft = min(minLeft, item.box.left)
                }
            }
        } else {
            var maxRight = xOrdered.first().box.right
            for (i in 1 until xOrdered.size) {
                val item = xOrdered[i]
                if (item.box.left >= maxRight) {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf(item)
                    maxRight = item.box.right
                } else {
                    currentGroup.add(item)
                    maxRight = max(maxRight, item.box.right)
                }
            }
        }

        groups.add(currentGroup)
        return groups
    }
}
