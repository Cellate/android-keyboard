package org.voxis.dictation.engine

import java.io.File

data class SpeechModel(
    val id: String,
    val displayName: String,
    val language: String,
    val languageCode: String,
    val approximateSizeMb: Int,
    val description: String,
    val downloadUrl: String,
    val fileName: String,
    val isBundled: Boolean = false,
    val isCustom: Boolean = false,
    var localPath: String? = null
) {
    fun isDownloaded(): Boolean {
        if (localPath.isNullOrBlank()) return false
        val file = File(localPath!!)
        return file.exists() && file.length() > 0
    }

    companion object {
        val HINGLISH_TINY = SpeechModel(
            id = "whisper_tiny_hinglish",
            displayName = "Whisper Tiny (Hinglish / Multilingual)",
            language = "Hinglish (Code-Mixed)",
            languageCode = "hi-en",
            approximateSizeMb = 75,
            description = "Fastest GGML model for code-mixed Hindi + English. Extremely low latency on mobile CPUs.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            fileName = "ggml-tiny.bin"
        )

        val HINGLISH_BASE = SpeechModel(
            id = "whisper_base_hinglish",
            displayName = "Whisper Base (Hinglish / Multilingual)",
            language = "Hinglish (Code-Mixed)",
            languageCode = "hi-en",
            approximateSizeMb = 142,
            description = "High accuracy for complex code-mixed Hindi & English vocabulary, colloquial phrases, and slang.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            fileName = "ggml-base.bin"
        )

        val ENGLISH_TINY = SpeechModel(
            id = "whisper_tiny_en",
            displayName = "Whisper Tiny (English)",
            language = "English",
            languageCode = "en",
            approximateSizeMb = 75,
            description = "Ultra-fast English speech recognition with lowest RAM & battery consumption.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
            fileName = "ggml-tiny.en.bin"
        )

        val ENGLISH_BASE = SpeechModel(
            id = "whisper_base_en",
            displayName = "Whisper Base (English)",
            language = "English",
            languageCode = "en",
            approximateSizeMb = 142,
            description = "Balanced accuracy and speed for English. Excellent comprehension of complex vocabulary.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
            fileName = "ggml-base.en.bin"
        )

        val HINDI_VOSK = SpeechModel(
            id = "vosk_hindi_small",
            displayName = "Vosk Hindi Small (Offline)",
            language = "Hindi (हिन्दी)",
            languageCode = "hi",
            approximateSizeMb = 42,
            description = "Lightweight offline Kaldi model specialized in native Hindi / Devanagari speech recognition.",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            fileName = "vosk-model-small-hi-0.22.zip"
        )

        val HINDI_WHISPER_SMALL = SpeechModel(
            id = "whisper_small_multi",
            displayName = "Whisper Small (Hindi / Indic Multilingual)",
            language = "Hindi / Indic",
            languageCode = "hi",
            approximateSizeMb = 466,
            description = "Deep neural model for high precision Hindi and regional Indic languages. Best accuracy when RAM permits.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            fileName = "ggml-small.bin"
        )

        // Backwards compatibility aliases
        val TinyEnglish: SpeechModel get() = HINGLISH_TINY
        val BaseEnglish: SpeechModel get() = ENGLISH_BASE
        val SmallMultilingual: SpeechModel get() = HINDI_WHISPER_SMALL

        val ALL_PREDEFINED: List<SpeechModel>
            get() = listOf(
                HINGLISH_TINY,
                HINGLISH_BASE,
                ENGLISH_TINY,
                ENGLISH_BASE,
                HINDI_VOSK,
                HINDI_WHISPER_SMALL
            )

        val ALL: List<SpeechModel>
            get() = ALL_PREDEFINED

        fun fromId(id: String): SpeechModel =
            ALL_PREDEFINED.find { it.id == id } ?: HINGLISH_TINY
    }
}
