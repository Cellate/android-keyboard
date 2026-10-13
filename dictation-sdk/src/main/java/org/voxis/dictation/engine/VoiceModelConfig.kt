package org.voxis.dictation.engine

import android.content.Context
import android.content.SharedPreferences

enum class VadSensitivity(val displayName: String, val threshold: Float) {
    LOW("Low Sensitivity (Noisy Environments)", 0.035f),
    MEDIUM("Medium Sensitivity (Recommended)", 0.018f),
    HIGH("High Sensitivity (Quiet / Whisper)", 0.008f)
}

enum class DecodingStrategy(val displayName: String, val description: String) {
    GREEDY("Greedy Search (Recommended)", "Fastest deterministic decoding with lowest mobile latency"),
    BEAM_SEARCH("Beam Search (High Accuracy)", "Explores multiple token hypotheses for noisy audio")
}

/**
 * User-configurable Voice Model & Audio Processing parameters.
 * Pre-populated with verified optimal defaults for on-device mobile execution.
 */
data class VoiceModelConfig(
    val nThreads: Int = RECOMMENDED_THREADS,
    val vadSilenceEndpointMs: Long = RECOMMENDED_SILENCE_MS,
    val languageCode: String = RECOMMENDED_LANGUAGE,
    val initialPrompt: String = RECOMMENDED_PROMPT,
    val autoPunctuate: Boolean = true,
    val autoCapitalize: Boolean = true,
    val vadSensitivity: VadSensitivity = VadSensitivity.MEDIUM,
    val decodingStrategy: DecodingStrategy = DecodingStrategy.GREEDY
) {
    companion object {
        const val RECOMMENDED_THREADS = 4
        const val RECOMMENDED_SILENCE_MS = 280L
        const val RECOMMENDED_LANGUAGE = "hi-en"
        const val RECOMMENDED_PROMPT = "Conversational Hinglish Latin script with natural punctuation: yaar, theek hai, kya scene hai, kaise ho, meeting schedule, call karo, bilkul."

        private const val PREFS_NAME = "voxis_voice_model_config"
        private const val KEY_THREADS = "n_threads"
        private const val KEY_SILENCE_MS = "vad_silence_endpoint_ms"
        private const val KEY_LANGUAGE = "language_code"
        private const val KEY_PROMPT = "initial_prompt"
        private const val KEY_PUNCTUATE = "auto_punctuate"
        private const val KEY_CAPITALIZE = "auto_capitalize"
        private const val KEY_SENSITIVITY = "vad_sensitivity"
        private const val KEY_DECODING = "decoding_strategy"

        fun getRecommended(): VoiceModelConfig = VoiceModelConfig()

        fun load(context: Context): VoiceModelConfig {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sensitivityStr = prefs.getString(KEY_SENSITIVITY, VadSensitivity.MEDIUM.name) ?: VadSensitivity.MEDIUM.name
            val decodingStr = prefs.getString(KEY_DECODING, DecodingStrategy.GREEDY.name) ?: DecodingStrategy.GREEDY.name

            val sensitivity = try {
                VadSensitivity.valueOf(sensitivityStr)
            } catch (_: Exception) {
                VadSensitivity.MEDIUM
            }

            val decoding = try {
                DecodingStrategy.valueOf(decodingStr)
            } catch (_: Exception) {
                DecodingStrategy.GREEDY
            }

            return VoiceModelConfig(
                nThreads = prefs.getInt(KEY_THREADS, RECOMMENDED_THREADS).coerceIn(1, 8),
                vadSilenceEndpointMs = prefs.getLong(KEY_SILENCE_MS, RECOMMENDED_SILENCE_MS).coerceIn(150L, 1000L),
                languageCode = prefs.getString(KEY_LANGUAGE, RECOMMENDED_LANGUAGE) ?: RECOMMENDED_LANGUAGE,
                initialPrompt = prefs.getString(KEY_PROMPT, RECOMMENDED_PROMPT) ?: RECOMMENDED_PROMPT,
                autoPunctuate = prefs.getBoolean(KEY_PUNCTUATE, true),
                autoCapitalize = prefs.getBoolean(KEY_CAPITALIZE, true),
                vadSensitivity = sensitivity,
                decodingStrategy = decoding
            )
        }

        fun save(context: Context, config: VoiceModelConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_THREADS, config.nThreads)
                .putLong(KEY_SILENCE_MS, config.vadSilenceEndpointMs)
                .putString(KEY_LANGUAGE, config.languageCode)
                .putString(KEY_PROMPT, config.initialPrompt)
                .putBoolean(KEY_PUNCTUATE, config.autoPunctuate)
                .putBoolean(KEY_CAPITALIZE, config.autoCapitalize)
                .putString(KEY_SENSITIVITY, config.vadSensitivity.name)
                .putString(KEY_DECODING, config.decodingStrategy.name)
                .apply()
        }
    }
}
