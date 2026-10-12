package org.voxis.dictation.client

import org.voxis.dictation.engine.SpeechModel

data class DictationOptions(
    val model: SpeechModel = SpeechModel.TinyEnglish,
    val continuousMode: Boolean = true,
    val autoPunctuate: Boolean = true,
    val autoCapitalize: Boolean = true,
    val preferBluetoothSco: Boolean = true,
    val requestAudioFocus: Boolean = true,
    val silenceTimeoutMs: Long = 1400L
)

interface DictationListener {
    fun onStateChanged(state: DictationState)
    fun onPartialTranscription(text: String)
    fun onFinalTranscription(text: String)
    fun onAudioLevel(normalizedLevel: Float)
    fun onError(error: String, cause: Throwable?)
}
