package org.voxis.dictation.client

import org.voxis.dictation.engine.SpeechModel

sealed interface DictationState {
    object Idle : DictationState
    data class Preparing(val model: SpeechModel) : DictationState
    data class Listening(val normalizedLevel: Float = 0f) : DictationState
    data class Transcribing(val partialText: String, val normalizedLevel: Float = 0f) : DictationState
    data class Complete(val finalText: String) : DictationState
    data class Error(val message: String, val cause: Throwable? = null) : DictationState
}
