package org.voxis.dictation.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dual-Mode VAD-Endpointed Whisper & Acoustic Engine.
 *
 * Supports both:
 * 1. Mode 1: Batch Record-then-Process execution.
 * 2. Mode 2: Real-time pause-endpointed continuous streaming.
 */
class VadEndpointedWhisperEngine(
    private val whisperEngine: WhisperNativeEngine,
    private val acousticFallback: StreamingAcousticEngine = StreamingAcousticEngine(),
    private val formatter: TextStreamFormatter = TextStreamFormatter()
) {
    companion object {
        private const val TAG = "VadWhisperEngine"
        private const val MIN_SPEECH_DURATION_MS = 400L
        private const val MAX_UTTERANCE_DURATION_MS = 15000L
        private const val SAMPLE_RATE = 16000
    }

    private val _finalizedSegmentFlow = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 32)
    val finalizedSegmentFlow: SharedFlow<TranscriptionResult> = _finalizedSegmentFlow.asSharedFlow()

    private val speechBuffer = ArrayList<Float>(SAMPLE_RATE * 15)
    private val bufferLock = Any()

    private var speechStartTimeMs: Long = 0L
    private var lastVoiceActivityTimeMs: Long = 0L
    private var isSpeechActive: Boolean = false

    fun processAudioFrame(
        frameSamples: FloatArray,
        isVoiceActive: Boolean,
        coroutineScope: CoroutineScope,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ) {
        val now = System.currentTimeMillis()
        var segmentToTranscribe: FloatArray? = null

        synchronized(bufferLock) {
            if (isVoiceActive) {
                if (!isSpeechActive) {
                    isSpeechActive = true
                    speechStartTimeMs = now
                }
                lastVoiceActivityTimeMs = now
                for (s in frameSamples) {
                    speechBuffer.add(s)
                }
            } else if (isSpeechActive) {
                // Keep trailing silence buffer up to endpoint duration
                for (s in frameSamples) {
                    speechBuffer.add(s)
                }

                val silenceDuration = now - lastVoiceActivityTimeMs
                val totalSpeechDuration = now - speechStartTimeMs

                val reachedPauseEndpoint = silenceDuration >= config.vadSilenceEndpointMs && totalSpeechDuration >= MIN_SPEECH_DURATION_MS
                val reachedMaxDuration = totalSpeechDuration >= MAX_UTTERANCE_DURATION_MS

                if (reachedPauseEndpoint || reachedMaxDuration) {
                    segmentToTranscribe = speechBuffer.toFloatArray()
                    speechBuffer.clear()
                    isSpeechActive = false
                }
            }
        }

        segmentToTranscribe?.let { audioChunk ->
            if (audioChunk.size >= (SAMPLE_RATE * MIN_SPEECH_DURATION_MS / 1000)) {
                coroutineScope.launch(Dispatchers.Default) {
                    executeInference(audioChunk, config)
                }
            }
        }
    }

    fun flushRemaining(
        coroutineScope: CoroutineScope,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ) {
        val remaining = synchronized(bufferLock) {
            if (speechBuffer.size >= (SAMPLE_RATE * 0.3)) {
                val copy = speechBuffer.toFloatArray()
                speechBuffer.clear()
                isSpeechActive = false
                copy
            } else {
                speechBuffer.clear()
                isSpeechActive = false
                null
            }
        }

        remaining?.let { chunk ->
            coroutineScope.launch(Dispatchers.Default) {
                executeInference(chunk, config)
            }
        }
    }

    /**
     * Executes batch inference on complete audio samples for Mode 1 (Record-then-Process).
     */
    suspend fun executeBatchInference(
        audioChunk: FloatArray,
        config: VoiceModelConfig = VoiceModelConfig.getRecommended()
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        executeInference(audioChunk, config)
    }

    private suspend fun executeInference(
        audioChunk: FloatArray,
        config: VoiceModelConfig
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        var textResult = ""

        // 1. If Whisper model is loaded in memory/JNI, run full GGML inference
        if (whisperEngine.isModelLoaded) {
            try {
                val rawWhisper = whisperEngine.transcribe(
                    audioSamples = audioChunk,
                    prompt = config.initialPrompt,
                    language = if (config.languageCode == "hi-en") "hi" else config.languageCode,
                    translate = false,
                    nThreads = config.nThreads
                ).trim()
                if (rawWhisper.isNotBlank()) {
                    textResult = rawWhisper
                }
            } catch (e: Exception) {
                Log.w(TAG, "Whisper model inference error: ${e.message}")
            }
        }

        // 2. If no Whisper model loaded or Whisper returned empty, run built-in offline acoustic decoder
        if (textResult.isBlank()) {
            textResult = acousticFallback.decodeBatchAudio(audioChunk, config)
        }

        val latencyMs = System.currentTimeMillis() - startTime
        val durationMs = (audioChunk.size * 1000L) / SAMPLE_RATE

        val formatted = formatter.format(textResult)

        val result = TranscriptionResult(
            text = formatted,
            isFinal = true,
            confidence = if (whisperEngine.isModelLoaded) 0.98f else 0.92f,
            durationMs = durationMs
        )

        _finalizedSegmentFlow.emit(result)
        result
    }

    fun reset() {
        synchronized(bufferLock) {
            speechBuffer.clear()
            isSpeechActive = false
            speechStartTimeMs = 0L
            lastVoiceActivityTimeMs = 0L
        }
    }
}
