package org.voxis.dictation.engine

import java.util.Locale

class TextStreamFormatter(
    var autoPunctuate: Boolean = true,
    var autoCapitalize: Boolean = true
) {
    private val punctuationCommands = mapOf(
        "period" to ".",
        "full stop" to ".",
        "comma" to ",",
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "colon" to ":",
        "semicolon" to ";",
        "new line" to "\n",
        "new paragraph" to "\n\n"
    )

    fun format(rawText: String, previousText: String = ""): String {
        var processed = rawText.trim()
        if (processed.isEmpty()) return ""

        // 1. Spoken punctuation substitution
        for ((word, symbol) in punctuationCommands) {
            val regex = Regex("\\b$word\\b", RegexOption.IGNORE_CASE)
            processed = processed.replace(regex, symbol)
        }

        // Clean up whitespace around punctuation
        processed = processed.replace(Regex("\\s+([.,?!:;])"), "$1")

        // 2. Auto-capitalization based on previous text context
        if (autoCapitalize) {
            val needsCapital = previousText.isEmpty() ||
                    previousText.trimEnd().endsWith(".") ||
                    previousText.trimEnd().endsWith("?") ||
                    previousText.trimEnd().endsWith("!") ||
                    previousText.trimEnd().endsWith("\n")

            if (needsCapital && processed.isNotEmpty()) {
                processed = processed.substring(0, 1).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                } + processed.substring(1)
            }
        }

        return processed
    }
}
