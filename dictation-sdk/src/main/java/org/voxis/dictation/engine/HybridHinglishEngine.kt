package org.voxis.dictation.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HinglishEngineMode(val displayName: String, val description: String) {
    HYBRID("Hybrid (Real-Time + Whisper)", "Instant words while speaking (<100ms) + Whisper sentence formatting on pause"),
    STREAMING_FAST("Fast Streaming (<100ms)", "Immediate word-by-word streaming with zero lag"),
    WHISPER_ENDPOINTED("Whisper VAD (<350ms)", "FUTO-style pause-triggered Whisper with full Hinglish punctuation")
}

data class HybridTranscriptionState(
    val liveText: String = "",
    val finalizedText: String = "",
    val interimWordText: String = "",
    val currentMode: HinglishEngineMode = HinglishEngineMode.HYBRID,
    val isSpeaking: Boolean = false
)

/**
 * Dual-Engine Hybrid Orchestrator for Conversational Hinglish.
 * Integrates instant real-time streaming with VAD-endpointed Whisper.
 */
class HybridHinglishEngine(
    private val whisperEngine: WhisperNativeEngine
) {
    val vadWhisper = VadEndpointedWhisperEngine(whisperEngine)
    val streamingAcoustic = StreamingAcousticEngine()

    private val _state = MutableStateFlow(HybridTranscriptionState())
    val state: StateFlow<HybridTranscriptionState> = _state.asStateFlow()

    fun attach(scope: CoroutineScope) {
        // Collect instant streaming tokens
        scope.launch {
            streamingAcoustic.partialTokenFlow.collect { interim ->
                if (_state.value.currentMode != HinglishEngineMode.WHISPER_ENDPOINTED) {
                    _state.update { current ->
                        val combined = if (current.finalizedText.isBlank()) {
                            interim
                        } else {
                            "${current.finalizedText} $interim"
                        }
                        current.copy(
                            liveText = combined,
                            interimWordText = interim
                        )
                    }
                }
            }
        }

        // Collect finalized Whisper segments on pause endpoints
        scope.launch {
            vadWhisper.finalizedSegmentFlow.collect { result ->
                _state.update { current ->
                    val newFinalized = if (current.finalizedText.isBlank()) {
                        result.text
                    } else {
                        "${current.finalizedText} ${result.text}"
                    }

                    // In hybrid mode, replace interim streaming text with Whisper's polished punctuation
                    current.copy(
                        finalizedText = newFinalized,
                        liveText = newFinalized,
                        interimWordText = ""
                    )
                }
                streamingAcoustic.reset()
            }
        }
    }

    fun processFrame(
        frameSamples: FloatArray,
        isVoiceActive: Boolean,
        coroutineScope: CoroutineScope
    ) {
        _state.update { it.copy(isSpeaking = isVoiceActive) }

        when (_state.value.currentMode) {
            HinglishEngineMode.HYBRID -> {
                streamingAcoustic.processFrame(frameSamples, isVoiceActive)
                vadWhisper.processAudioFrame(frameSamples, isVoiceActive, coroutineScope)
            }
            HinglishEngineMode.STREAMING_FAST -> {
                streamingAcoustic.processFrame(frameSamples, isVoiceActive)
            }
            HinglishEngineMode.WHISPER_ENDPOINTED -> {
                vadWhisper.processAudioFrame(frameSamples, isVoiceActive, coroutineScope)
            }
        }
    }

    fun onRecordingStopped(coroutineScope: CoroutineScope) {
        vadWhisper.flushRemaining(coroutineScope)
        _state.update { it.copy(isSpeaking = false) }
    }

    fun setMode(mode: HinglishEngineMode) {
        _state.update { it.copy(currentMode = mode) }
    }

    fun clear() {
        vadWhisper.reset()
        streamingAcoustic.reset()
        _state.update {
            it.copy(
                liveText = "",
                finalizedText = "",
                interimWordText = ""
            )
        }
    }
}
