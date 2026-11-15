package com.openscreentranslator.app.translation

import com.openscreentranslator.app.data.AppLanguages

/**
 * Pure JVM helper for building & parsing comic-context prompts for Hy-MT2-1.8B and Gemini.
 */
object TranslationBatchHelper {

    private val TAG_HEADER_REGEX = Regex("(?:\\[\\[B(\\d+)]]|\\[B(\\d+)])")
    private val HY_SPECIAL_TOKEN_REGEX = Regex("<｜[^｜]+｜>")

    fun languageEnglishName(code: String): String = AppLanguages.englishName(code)

    fun buildSingleHymtPrompt(sourceText: String, targetLang: String): String {
        val targetName = languageEnglishName(targetLang)
        return "Translate the following text into $targetName. " +
            "Note that you should only output the translated result without any additional explanation:\n\n" +
            sourceText
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
