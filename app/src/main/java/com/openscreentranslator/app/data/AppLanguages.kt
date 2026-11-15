package com.openscreentranslator.app.data

/**
 * Single source of truth for supported source/target languages and pure JVM script-detection
 * heuristics used when reading multi-language pages simultaneously (`sourceLanguage == AUTO`).
 */
object AppLanguages {

    const val AUTO = "auto"
    const val DEFAULT_TARGET = "vi"

    data class LanguageOption(
        val code: String,
        val shortCode: String,
        val labelVi: String,
        val englishName: String,
        val ocrSupported: Boolean = true,
        val supportsTarget: Boolean = true
    )

    val ALL_OPTIONS: List<LanguageOption> = listOf(
        LanguageOption(
            code = AUTO,
            shortCode = "AUTO",
            labelVi = "Tự phát hiện (Đa ngôn ngữ)",
            englishName = "Auto-detect",
            ocrSupported = true,
            supportsTarget = false
        ),
        LanguageOption("vi", "VI", "Tiếng Việt (VI)", "Vietnamese"),
        LanguageOption("en", "EN", "Tiếng Anh (EN)", "English"),
        LanguageOption("ja", "JA", "Tiếng Nhật (JA)", "Japanese"),
        LanguageOption("zh", "ZH", "Tiếng Trung (ZH)", "Chinese"),
        LanguageOption("ko", "KO", "Tiếng Hàn (KO)", "Korean"),
        LanguageOption("fr", "FR", "Tiếng Pháp (FR)", "French"),
        LanguageOption("de", "DE", "Tiếng Đức (DE)", "German"),
        LanguageOption("es", "ES", "Tiếng Tây Ban Nha (ES)", "Spanish"),
        LanguageOption("pt", "PT", "Tiếng Bồ Đào Nha (PT)", "Portuguese"),
        LanguageOption("id", "ID", "Tiếng Indonesia (ID)", "Indonesian"),
        LanguageOption("it", "IT", "Tiếng Ý (IT)", "Italian"),
        LanguageOption("nl", "NL", "Tiếng Hà Lan (NL)", "Dutch"),
        LanguageOption("pl", "PL", "Tiếng Ba Lan (PL)", "Polish"),
        LanguageOption("tr", "TR", "Tiếng Thổ Nhĩ Kỳ (TR)", "Turkish"),
        LanguageOption("ru", "RU", "Tiếng Nga (RU)", "Russian", ocrSupported = false),
        LanguageOption("th", "TH", "Tiếng Thái (TH)", "Thai", ocrSupported = false),
        LanguageOption("ar", "AR", "Tiếng Ả Rập (AR)", "Arabic", ocrSupported = false)
    )

    private val byCode: Map<String, LanguageOption> = ALL_OPTIONS.associateBy { it.code }

    val SOURCE_OPTIONS: List<LanguageOption> = ALL_OPTIONS.filter { it.ocrSupported }
    val TARGET_OPTIONS: List<LanguageOption> = ALL_OPTIONS.filter { it.supportsTarget }

    private val VIETNAMESE_UNIQUE_CHARS = setOf(
        'ă', 'đ', 'ơ', 'ư',
        'ả', 'ạ', 'ằ', 'ắ', 'ẳ', 'ẵ', 'ặ',
        'ầ', 'ấ', 'ẩ', 'ẫ', 'ậ', 'ẻ', 'ẹ',
        'ề', 'ế', 'ể', 'ễ', 'ệ', 'ỉ', 'ị',
        'ỏ', 'ọ', 'ồ', 'ố', 'ổ', 'ỗ', 'ộ',
        'ờ', 'ớ', 'ở', 'ỡ', 'ợ', 'ủ', 'ụ',
        'ừ', 'ứ', 'ử', 'ữ', 'ự', 'ỷ', 'ỵ'
    )

    fun find(code: String): LanguageOption? = byCode[code.lowercase()]

    fun shortCode(code: String): String = find(code)?.shortCode ?: code.uppercase()

    fun englishName(code: String): String {
        val option = find(code)
        if (option != null) return option.englishName
        if (code.equals(AUTO, ignoreCase = true) || code.isBlank()) return "Auto-detect"
        val display = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
        return display.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: "Auto-detect"
    }

    fun isOcrSupported(code: String): Boolean = find(code)?.ocrSupported == true

    fun isKanaChar(ch: Char): Boolean {
        return ch in '\u3040'..'\u309F' ||
            ch in '\u30A0'..'\u30FF' ||
            ch in '\uFF66'..'\uFF9F'
    }

    fun isHangulChar(ch: Char): Boolean {
        return ch in '\uAC00'..'\uD7AF' ||
            ch in '\u1100'..'\u11FF' ||
            ch in '\u3130'..'\u318F'
    }

    fun isHanChar(ch: Char): Boolean {
        return ch in '\u3400'..'\u4DBF' ||
            ch in '\u4E00'..'\u9FFF' ||
            ch in '\uF900'..'\uFAFF'
    }

    fun containsKana(text: String): Boolean = text.any { isKanaChar(it) }

    fun containsHangul(text: String): Boolean = text.any { isHangulChar(it) }

    fun containsHan(text: String): Boolean = text.any { isHanChar(it) }

    fun containsCjkOrHangul(text: String): Boolean =
        text.any { isKanaChar(it) || isHangulChar(it) || isHanChar(it) }

    fun hasVietnameseDiacritics(text: String): Boolean {
        return text.any { ch -> ch.lowercaseChar() in VIETNAMESE_UNIQUE_CHARS }
    }

    /**
     * Fast deterministic language detection from Unicode scripts.
     * Returns ISO-639-1 (`"ja"`, `"ko"`, `"zh"`, `"vi"`) when script uniquely determines the language,
     * or `null` for ambiguous Latin strings (e.g. English vs French vs Indonesian) so ML Kit
     * LanguageIdentification can classify them.
     */
    fun detectDeterministicScriptLanguage(text: String, pageHasKana: Boolean): String? {
        if (text.isBlank()) return null

        var kanaCount = 0
        var hangulCount = 0
        var hanCount = 0

        for (ch in text) {
            when {
                isKanaChar(ch) -> kanaCount++
                isHangulChar(ch) -> hangulCount++
                isHanChar(ch) -> hanCount++
            }
        }

        if (kanaCount > 0) return "ja"
        if (hangulCount > 0 && hangulCount >= hanCount) return "ko"
        if (hanCount > 0) {
            return if (pageHasKana) "ja" else "zh"
        }
        if (hasVietnameseDiacritics(text)) return "vi"
        return null
    }

    /**
     * Normalizes BCP-47 tags returned by ML Kit LanguageIdentification (e.g. `"zh-Hant"` -> `"zh"`,
     * `"und"` -> `null`, `"-Latn"` transliterations -> `null`).
     */
    fun normalizeDetectedTag(rawTag: String?): String? {
        if (rawTag.isNullOrBlank()) return null
        val normalized = rawTag.trim().lowercase()
        if (normalized == "und") return null
        if (normalized.endsWith("-latn")) return null
        val base = normalized.substringBefore('-')
        if (base == "fil") return "tl"
        return base.takeIf { it.length in 2..3 }
    }
}
