package org.voxis.dictation.engine

/**
 * Configuration and prompt conditioning constants specifically engineered
 * for conversational Hinglish (Hindi-English code-switching in Latin script).
 */
object HinglishPromptConfig {

    /**
     * Primary steering prompt to guide Whisper's decoder into outputting colloquial
     * Hindi words in Romanized Latin script instead of Devanagari, maintaining English
     * technical and conversational words directly.
     */
    const val DEFAULT_HINGLISH_PROMPT: String =
        "Hinglish speech transcription, conversational tone, Latin script with punctuation: yaar, theek hai, kya scene hai, kaise ho."

    /**
     * Base language code: "hi" activates Whisper's Hindi phoneme acoustic modeling,
     * while the Latin initial prompt biases tokenizer output into code-mixed Latin script.
     */
    const val DEFAULT_LANGUAGE: String = "hi"

    /**
     * Never translate when doing Hinglish transcription.
     */
    const val DEFAULT_TRANSLATE: Boolean = false

    /**
     * Optimal thread count for modern ARM big.LITTLE architectures (usually 4 performance cores).
     */
    const val RECOMMENDED_THREADS: Int = 4

    /**
     * Target sample rate expected by Whisper (16 kHz).
     */
    const val SAMPLE_RATE_HZ: Int = 16000

    /**
     * Chunk duration thresholds in milliseconds:
     * - Minimum speech duration before triggering inference: ~1.5s
     * - Target inference buffer window: ~3.0s (48,000 samples)
     * - Maximum sliding window: ~5.0s (80,000 samples)
     */
    const val MIN_CHUNK_DURATION_MS: Long = 1500L
    const val TARGET_CHUNK_DURATION_MS: Long = 3000L
    const val MAX_BUFFER_DURATION_MS: Long = 5000L

    const val TARGET_CHUNK_SAMPLES: Int = ((SAMPLE_RATE_HZ * TARGET_CHUNK_DURATION_MS) / 1000).toInt()
    const val MAX_BUFFER_SAMPLES: Int = ((SAMPLE_RATE_HZ * MAX_BUFFER_DURATION_MS) / 1000).toInt()
}
