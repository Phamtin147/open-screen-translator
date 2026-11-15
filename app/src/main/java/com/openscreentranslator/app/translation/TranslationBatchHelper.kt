package com.openscreentranslator.app.translation

/**
 * Pure JVM helper for building & parsing comic-context prompts for Hy-MT2-1.8B and Gemini.
 */
object TranslationBatchHelper {

    private val TAG_HEADER_REGEX = Regex("(?:\\[\\[B(\\d+)]]|\\[B(\\d+)])")
    private val HY_SPECIAL_TOKEN_REGEX = Regex("<｜[^｜]+｜>")

    fun languageEnglishName(code: String): String = when (code) {
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh" -> "Chinese"
        "vi" -> "Vietnamese"
        else -> "English"
    }

    fun buildSingleHymtPrompt(sourceText: String, targetLang: String): String {
        val targetName = languageEnglishName(targetLang)
        return "Translate the following text into $targetName. " +
            "Note that you should only output the translated result without any additional explanation:\n\n" +
            sourceText
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
        includeContextInstruction: Boolean = true
    ): String {
        val targetName = languageEnglishName(targetLang)
        val sourceName = languageEnglishName(sourceLang)
        val payload = bubbles.mapIndexed { idx, text ->
            val sanitized = text.replace("\n", " ").trim()
            "[[B${idx + 1}]] $sanitized"
        }.joinToString("\n")

        val contextNote = if (includeContextInstruction) {
            "These lines are sequential speech bubbles from one comic/screen page in $sourceName. " +
                "Keep character pronouns, speaker relationship, and tone consistent across bubbles.\n"
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
