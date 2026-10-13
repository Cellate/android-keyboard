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
    val engineName: String = "No Model Loaded",
    val hasModel: Boolean = false
)

/**
 * Dual-Engine Orchestrator for Conversational Dictation.
 * Seamlessly manages Mode 1 (Record-then-Process) and Mode 2 (Live Real-Time Streaming)
 * utilizing genuine Vosk Kaldi neural ASR and Whisper GGML models.
 */
class HybridHinglishEngine(
    private val whisperEngine: WhisperNativeEngine,
    val voskEngine: VoskEngine = VoskEngine()
) {
    val vadWhisper = VadEndpointedWhisperEngine(whisperEngine, voskEngine)

    private val _state = MutableStateFlow(HybridTranscriptionState())
    val state: StateFlow<HybridTranscriptionState> = _state.asStateFlow()

    fun attach(scope: CoroutineScope) {
        // Collect finalized Whisper/Vosk segments on pause endpoints
        scope.launch {
            vadWhisper.finalizedSegmentFlow.collect { result ->
                if (result.text.isNotBlank()) {
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
                            lastLatencyMs = result.durationMs,
                            engineName = vadWhisper.activeEngineName,
                            hasModel = vadWhisper.isModelLoaded
                        )
                    }
                }
            }
        }
    }

    fun processFrame(
        frameSamples: FloatArray,
        isVoiceActive: Boolean,
        coroutineScope: CoroutineScope,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ) {
        _state.update {
            it.copy(
                isSpeaking = isVoiceActive,
                engineName = vadWhisper.activeEngineName,
                hasModel = vadWhisper.isModelLoaded
            )
        }

        if (_state.value.currentMode == HinglishEngineMode.LIVE_REALTIME) {
            // Mode 2: Live streaming speech recognition via Vosk or pause-endpointed Whisper
            if (voskEngine.isModelLoaded) {
                val (recognizedText, isFinal) = voskEngine.processFrame(frameSamples)
                if (recognizedText.isNotBlank()) {
                    _state.update { current ->
                        if (isFinal) {
                            val newFinal = if (current.finalizedText.isBlank()) recognizedText else "${current.finalizedText} $recognizedText"
                            current.copy(
                                finalizedText = newFinal,
                                liveText = newFinal,
                                interimWordText = ""
                            )
                        } else {
                            val combined = if (current.finalizedText.isBlank()) recognizedText else "${current.finalizedText} $recognizedText"
                            current.copy(
                                liveText = combined,
                                interimWordText = recognizedText
                            )
                        }
                    }
                }
            } else {
                vadWhisper.processAudioFrame(frameSamples, isVoiceActive, coroutineScope, config)
            }
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
                engineName = vadWhisper.activeEngineName,
                hasModel = vadWhisper.isModelLoaded
            )
        }
        return result
    }

    fun onRecordingStopped(
        coroutineScope: CoroutineScope,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ) {
        if (_state.value.currentMode == HinglishEngineMode.LIVE_REALTIME) {
            if (voskEngine.isModelLoaded) {
                val finalVosk = voskEngine.getFinalResult()
                if (finalVosk.isNotBlank()) {
                    _state.update { current ->
                        val newFinal = if (current.finalizedText.isBlank()) finalVosk else "${current.finalizedText} $finalVosk"
                        current.copy(
                            finalizedText = newFinal,
                            liveText = newFinal,
                            interimWordText = ""
                        )
                    }
                }
                voskEngine.reset()
            } else {
                vadWhisper.flushRemaining(coroutineScope, config)
            }
        }
        _state.update { it.copy(isSpeaking = false) }
    }

    fun setMode(mode: HinglishEngineMode) {
        _state.update { it.copy(currentMode = mode) }
    }

    fun clear() {
        vadWhisper.reset()
        voskEngine.reset()
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
