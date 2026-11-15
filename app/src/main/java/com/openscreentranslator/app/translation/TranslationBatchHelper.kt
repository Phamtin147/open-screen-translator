package com.openscreentranslator.app.translation

import com.openscreentranslator.app.data.AppLanguages

/**
 * Pure JVM helper for building & parsing comic-context prompts for Hy-MT2-1.8B and Gemini.
 */
object TranslationBatchHelper {

    private val TAG_HEADER_REGEX = Regex("(?:\\[\\[B(\\d+)]]|\\[B(\\d+)])")
    private val HY_SPECIAL_TOKEN_REGEX = Regex("<｜[^｜]+｜>")

    fun languageEnglishName(code: String): String = AppLanguages.englishName(code)

    private val URL_OR_HOST_PATH_REGEX =
        Regex("^(?:https?://\\S+|(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?::\\d+)?/\\S*|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,})$")
    private val CAPS_PRONOUN_I_REGEX = Regex("(?<![\\p{L}])i(?=(?:'[a-z]{1,2})?(?![\\p{L}]))")

    fun shouldSkipTranslation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val letterCount = trimmed.count { it.isLetter() }
        if (letterCount == 0) return true
        if (trimmed.length == 1 && trimmed[0].code < 128) return true
        val hasDigit = trimmed.any { it.isDigit() }
        val hasCjkOrHangul = trimmed.any {
            com.openscreentranslator.app.data.AppLanguages.isKanaChar(it) ||
                com.openscreentranslator.app.data.AppLanguages.isHanChar(it) ||
                com.openscreentranslator.app.data.AppLanguages.isHangulChar(it)
        }
        // Status bar clocks / signal icons OCR'd as e.g. "9:48 3 M" or "4G"
        if (!hasCjkOrHangul && hasDigit && letterCount <= 1) return true
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size == 1) {
            val tok = tokens[0]
            if (tok.contains("://") || URL_OR_HOST_PATH_REGEX.matches(tok.lowercase(java.util.Locale.ROOT))) {
                return true
            }
        } else if (tokens.size <= 3) {
            // Browser URL bar often has 1-2 char address-bar icons before/after the URL (e.g. "O 127.0.0.1:18765/page.html")
            val nonIconTokens = tokens.filter { it.length > 2 || it.contains('.') || it.contains('/') }
            if (nonIconTokens.size == 1) {
                val urlCandidate = nonIconTokens[0]
                if (urlCandidate.contains("://") ||
                    URL_OR_HOST_PATH_REGEX.matches(urlCandidate.lowercase(java.util.Locale.ROOT))
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun isCjkNoSpaceChar(ch: Char): Boolean {
        if (com.openscreentranslator.app.data.AppLanguages.isKanaChar(ch) ||
            com.openscreentranslator.app.data.AppLanguages.isHanChar(ch)
        ) {
            return true
        }
        return ch in charArrayOf('。', '、', '！', '？', '…', '「', '」', '『', '』', '（', '）', '【', '】', 'ー', '〜')
    }

    fun normalizeComicTextForHymt(rawText: String): String {
        val lines = rawText.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""

        val joinedBuilder = StringBuilder()
        for (line in lines) {
            if (joinedBuilder.isEmpty()) {
                joinedBuilder.append(line)
            } else {
                val prevLast = joinedBuilder[joinedBuilder.length - 1]
                val nextFirst = line[0]
                val bothCjkNoSpace = isCjkNoSpaceChar(prevLast) && isCjkNoSpaceChar(nextFirst)
                if (bothCjkNoSpace) {
                    joinedBuilder.append(line)
                } else {
                    joinedBuilder.append(' ').append(line)
                }
            }
        }
        val collapsed = joinedBuilder.toString().replace(Regex(" {2,}"), " ").trim()

        // Normalize ALL-CAPS English/Latin comic dialogue to natural sentence case so 1.8B BPE translates idioms accurately
        var latinCount = 0
        var lowerCount = 0
        for (ch in collapsed) {
            if (ch.isLetter() &&
                !com.openscreentranslator.app.data.AppLanguages.isKanaChar(ch) &&
                !com.openscreentranslator.app.data.AppLanguages.isHanChar(ch) &&
                !com.openscreentranslator.app.data.AppLanguages.isHangulChar(ch)
            ) {
                latinCount++
                if (ch.isLowerCase()) lowerCount++
            }
        }
        if (latinCount >= 6 && lowerCount == 0 && collapsed.count { it == ' ' } >= 1) {
            val lowered = collapsed.lowercase(java.util.Locale.ROOT)
            val withPronounI = CAPS_PRONOUN_I_REGEX.replace(lowered) { "I" }
            var firstCharDone = false
            val sb = StringBuilder(withPronounI.length)
            var capitalizeNext = true
            for (ch in withPronounI) {
                if (ch.isLetter()) {
                    sb.append(if (capitalizeNext || !firstCharDone) ch.titlecaseChar() else ch)
                    firstCharDone = true
                    capitalizeNext = false
                } else {
                    sb.append(ch)
                    if (ch == '.' || ch == '!' || ch == '?') {
                        capitalizeNext = true
                    }
                }
            }
            return sb.toString()
        }
        return collapsed
    }

    fun stripRedundantOuterQuotes(sourceText: String, translated: String): String {
        val trimmed = translated.trim()
        if (trimmed.length < 2) return trimmed
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'', '«' to '»')
        val srcTrimmed = sourceText.trim()
        for ((open, close) in pairs) {
            if (trimmed.first() == open && trimmed.last() == close) {
                val srcQuoted = srcTrimmed.firstOrNull() == open && srcTrimmed.lastOrNull() == close
                if (!srcQuoted) {
                    return trimmed.substring(1, trimmed.length - 1).trim()
                }
            }
        }
        return trimmed
    }

    fun buildSingleHymtPrompt(sourceText: String, targetLang: String): String {
        val targetName = languageEnglishName(targetLang)
        val normalized = normalizeComicTextForHymt(sourceText)
        return "Translate the following segment into $targetName, without additional explanation.\n\n$normalized"
    }

    fun buildGeminiSinglePrompt(
        sourceText: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val sourceLine = if (sourceLang == AppLanguages.AUTO || sourceLang.isBlank()) {
            "Automatically detect the source language (the snippet may mix multiple languages)."
        } else {
            "Source language: ${languageEnglishName(sourceLang)}."
        }
        return buildSingleHymtPrompt(sourceText = sourceText, targetLang = targetLang) +
            "\n$sourceLine"
    }

    fun buildContextSingleHymtPrompt(
        sourceText: String,
        pageContextLines: List<String>,
        targetLang: String
    ): String {
        val targetName = languageEnglishName(targetLang)
        val background = pageContextLines.mapIndexed { idx, line -> "[B${idx + 1}] $line" }.joinToString("\n")
        return "[Background Information]\n" +
            "$background\n\n" +
            "Please translate the following text into $targetName, taking the provided background information into consideration. " +
            "Output ONLY the translated result without any tags or explanation:\n\n" +
            "[Source Text]\n" +
            sourceText
    }

    fun buildTaggedBatchPrompt(
        bubbles: List<String>,
        targetLang: String,
        sourceLang: String,
        includeContextInstruction: Boolean = true,
        bubbleDetectedLangs: List<String?> = emptyList()
    ): String {
        val targetName = languageEnglishName(targetLang)
        val payload = bubbles.mapIndexed { idx, text ->
            val sanitized = text.replace("\n", " ").trim()
            "[[B${idx + 1}]] $sanitized"
        }.joinToString("\n")

        val contextNote = if (includeContextInstruction) {
            if (sourceLang == AppLanguages.AUTO) {
                val detectedNames = bubbleDetectedLangs
                    .filterNotNull()
                    .filter { it != AppLanguages.AUTO }
                    .distinct()
                    .map { languageEnglishName(it) }
                when {
                    detectedNames.size >= 2 ->
                        "These lines are sequential speech bubbles from one comic/screen page containing multiple source languages (${detectedNames.joinToString(", ")}). " +
                            "Detect each bubble's language individually, translate ALL bubbles into $targetName, and keep character pronouns, speaker relationship, and tone consistent across bubbles.\n"
                    detectedNames.size == 1 ->
                        "These lines are sequential speech bubbles from one comic/screen page (primarily ${detectedNames.first()}, possibly mixed with other languages). " +
                            "Auto-detect each bubble's language, translate ALL bubbles into $targetName, and keep character pronouns, speaker relationship, and tone consistent across bubbles.\n"
                    else ->
                        "These lines are sequential speech bubbles from one comic/screen page and may contain multiple source languages mixed together. " +
                            "Auto-detect each bubble's language, translate ALL bubbles into $targetName, and keep character pronouns, speaker relationship, and tone consistent across bubbles.\n"
                }
            } else {
                val sourceName = languageEnglishName(sourceLang)
                "These lines are sequential speech bubbles from one comic/screen page in $sourceName. " +
                    "Keep character pronouns, speaker relationship, and tone consistent across bubbles.\n"
            }
        } else {
            ""
        }

        return "Please accurately translate the following dialogue into $targetName.\n" +
            contextNote +
            "You must retain the exact same numbered delimiter tags [[B1]], [[B2]], ... before each translated line. " +
            "Strictly do not omit, escape, renumber, or translate these delimiter tags, and output ONLY the tagged translations:\n\n" +
            payload
    }

    fun parseTaggedBatchResponse(rawOutput: String, expectedCount: Int): Map<Int, String> {
        val cleaned = stripSpecialAndFences(rawOutput)
        if (cleaned.isEmpty() || expectedCount <= 0) return emptyMap()

        val matches = TAG_HEADER_REGEX.findAll(cleaned).toList()
        if (matches.isEmpty()) return emptyMap()

        val result = mutableMapOf<Int, String>()
        for (i in matches.indices) {
            val match = matches[i]
            val rawIdx = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val indexNum = rawIdx.toIntOrNull() ?: continue
            if (indexNum !in 1..expectedCount) continue

            val contentStart = match.range.last + 1
            val contentEnd = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length
            if (contentStart <= contentEnd) {
                val value = cleaned.substring(contentStart, contentEnd)
                    .trim()
                    .trimStart(':', '-', ' ')
                    .trim()
                if (value.isNotEmpty()) {
                    result[indexNum] = value
                }
            }
        }
        return result
    }

    fun stripSpecialAndFences(raw: String): String {
        var s = HY_SPECIAL_TOKEN_REGEX.replace(raw, "").trim()
        if (s.startsWith("```") && s.endsWith("```")) {
            s = s.removePrefix("```").removeSuffix("```").trim()
        }
        return s
    }

    fun cleanSingleOutput(raw: String): String {
        var s = stripSpecialAndFences(raw)
        s = s.replace(Regex("^\\[\\[?B\\d+]?]\\s*"), "")
        return s.trim()
    }
}
