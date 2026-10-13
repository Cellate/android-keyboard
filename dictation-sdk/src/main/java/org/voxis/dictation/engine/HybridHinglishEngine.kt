package org.voxis.dictation.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HinglishEngineMode(val displayName: String, val description: String) {
    RECORD_THEN_PROCESS("Batch (Record → Process)", "Speak, stop mic, then model decodes complete utterance with pipeline metrics"),
    LIVE_REALTIME("⚡ Live Real-Time", "Instant word-by-word streaming (<100ms) with pause-endpointed sentence stabilization")
}

data class HybridTranscriptionState(
    val liveText: String = "",
    val finalizedText: String = "",
    val interimWordText: String = "",
    val currentMode: HinglishEngineMode = HinglishEngineMode.LIVE_REALTIME,
    val isSpeaking: Boolean = false,
    val lastLatencyMs: Long = 0L,
    val engineName: String = "Embedded Offline Engine"
)

/**
 * Dual-Engine Orchestrator for Conversational Dictation.
 * Seamlessly manages Mode 1 (Record-then-Process) and Mode 2 (Live Real-Time Streaming).
 */
class HybridHinglishEngine(
    private val whisperEngine: WhisperNativeEngine
) {
    val streamingAcoustic = StreamingAcousticEngine()
    val vadWhisper = VadEndpointedWhisperEngine(whisperEngine, streamingAcoustic)

    private val _state = MutableStateFlow(HybridTranscriptionState())
    val state: StateFlow<HybridTranscriptionState> = _state.asStateFlow()

    fun attach(scope: CoroutineScope) {
        // Collect instant streaming tokens in live real-time mode
        scope.launch {
            streamingAcoustic.partialTokenFlow.collect { interim ->
                if (_state.value.currentMode == HinglishEngineMode.LIVE_REALTIME) {
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

        // Collect finalized Whisper/Acoustic segments on pause endpoints
        scope.launch {
            vadWhisper.finalizedSegmentFlow.collect { result ->
                _state.update { current ->
                    val newFinalized = if (current.finalizedText.isBlank()) {
                        result.text
                    } else {
                        "${current.finalizedText} ${result.text}"
                    }

                    current.copy(
                        finalizedText = newFinalized,
                        liveText = newFinalized,
                        interimWordText = "",
                        lastLatencyMs = result.durationMs
                    )
                }
                streamingAcoustic.reset()
            }
        }
    }

    suspend fun processFrame(
        frameSamples: FloatArray,
        isVoiceActive: Boolean,
        coroutineScope: CoroutineScope,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ) {
        _state.update { it.copy(isSpeaking = isVoiceActive) }

        if (_state.value.currentMode == HinglishEngineMode.LIVE_REALTIME) {
            streamingAcoustic.processFrame(frameSamples, isVoiceActive, config)
            vadWhisper.processAudioFrame(frameSamples, isVoiceActive, coroutineScope, config)
        }
    }

    suspend fun processBatchAudio(
        samples: FloatArray,
        config: VoiceModelConfig
    ): TranscriptionResult {
        val start = System.currentTimeMillis()
        val result = vadWhisper.executeBatchInference(samples, config)
        val elapsed = System.currentTimeMillis() - start

        _state.update { current ->
            current.copy(
                liveText = result.text,
                finalizedText = result.text,
                interimWordText = "",
                lastLatencyMs = elapsed,
                isSpeaking = false,
                engineName = if (whisperEngine.isModelLoaded) "Whisper GGML (${config.nThreads} threads)" else "Embedded Offline Engine"
            )
        }
        return result
    }

    fun onRecordingStopped(
        coroutineScope: CoroutineScope,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ) {
        if (_state.value.currentMode == HinglishEngineMode.LIVE_REALTIME) {
            vadWhisper.flushRemaining(coroutineScope, config)
        }
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
                interimWordText = "",
                lastLatencyMs = 0L
            )
        }
    }
}
