package org.voxis.dictation.engine

sealed class SpeechModel(
    val id: String,
    val displayName: String,
    val language: String,
    val approximateSizeMb: Int,
    val description: String,
    val isBundled: Boolean = false
) {
    object TinyEnglish : SpeechModel(
        id = "whisper_tiny_en",
        displayName = "Whisper Tiny (English)",
        language = "en",
        approximateSizeMb = 39,
        description = "Ultra-fast, lowest battery and RAM consumption. Ideal for real-time mobile dictation.",
        isBundled = true
    )

    object BaseEnglish : SpeechModel(
        id = "whisper_base_en",
        displayName = "Whisper Base (English)",
        language = "en",
        approximateSizeMb = 73,
        description = "Balanced accuracy and speed. Excellent comprehension of complex vocabulary.",
        isBundled = false
    )

    object SmallMultilingual : SpeechModel(
        id = "whisper_small_multi",
        displayName = "Whisper Small (Multilingual)",
        language = "multi",
        approximateSizeMb = 244,
        description = "High accuracy across 90+ languages with automatic language detection.",
        isBundled = false
    )

    companion object {
        val ALL: List<SpeechModel>
            get() = listOf(TinyEnglish, BaseEnglish, SmallMultilingual)

        fun fromId(id: String): SpeechModel =
            ALL.find { it.id == id } ?: TinyEnglish
    }
}
