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
 * FUTO-Style VAD-Endpointed Whisper Engine.
 *
 * Eliminates Whisper's sliding-window latency on mobile:
 * - Buffers audio during continuous speech without triggering expensive multi-second re-encodings.
 * - Detects natural conversational pauses (250ms to 350ms silence endpoint).
 * - Dispatches discrete, bounded speech segments directly to Whisper JNI with greedy decoding.
 * - Emits clean, punctuated Hinglish in Latin script during the speaker's natural breathing pause.
 */
class VadEndpointedWhisperEngine(
    private val whisperEngine: WhisperNativeEngine,
    private val formatter: TextStreamFormatter = TextStreamFormatter()
) {
    companion object {
        private const val TAG = "VadWhisperEngine"
        private const val SILENCE_ENDPOINT_MS = 280L // Natural conversational pause threshold
        private const val MIN_SPEECH_DURATION_MS = 600L
        private const val MAX_UTTERANCE_DURATION_MS = 4500L
        private const val SAMPLE_RATE = HinglishPromptConfig.SAMPLE_RATE_HZ
    }

    private val _finalizedSegmentFlow = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 16)
    val finalizedSegmentFlow: SharedFlow<TranscriptionResult> = _finalizedSegmentFlow.asSharedFlow()

    private val speechBuffer = ArrayList<Float>(SAMPLE_RATE * 5)
    private val bufferLock = Any()

    private var speechStartTimeMs: Long = 0L
    private var lastVoiceActivityTimeMs: Long = 0L
    private var isSpeechActive: Boolean = false

    fun processAudioFrame(
        frameSamples: FloatArray,
        isVoiceActive: Boolean,
        coroutineScope: CoroutineScope
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

                val reachedPauseEndpoint = silenceDuration >= SILENCE_ENDPOINT_MS && totalSpeechDuration >= MIN_SPEECH_DURATION_MS
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
                    executeEndpointedInference(audioChunk)
                }
            }
        }
    }

    fun flushRemaining(coroutineScope: CoroutineScope) {
        val remaining = synchronized(bufferLock) {
            if (speechBuffer.size >= (SAMPLE_RATE * 0.4)) {
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
                executeEndpointedInference(chunk)
            }
        }
    }

    private suspend fun executeEndpointedInference(audioChunk: FloatArray) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val rawText = whisperEngine.transcribe(
            audioSamples = audioChunk,
            prompt = HinglishPromptConfig.DEFAULT_HINGLISH_PROMPT,
            language = HinglishPromptConfig.DEFAULT_LANGUAGE,
            translate = HinglishPromptConfig.DEFAULT_TRANSLATE,
            nThreads = HinglishPromptConfig.RECOMMENDED_THREADS
        ).trim()

        val latencyMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Endpointed Whisper transcribed in ${latencyMs}ms: '$rawText'")

        if (rawText.isNotBlank()) {
            val formatted = formatter.format(rawText)
            val durationMs = (audioChunk.size * 1000L) / SAMPLE_RATE

            _finalizedSegmentFlow.emit(
                TranscriptionResult(
                    text = formatted,
                    isFinal = true,
                    confidence = 0.96f,
                    durationMs = durationMs
                )
            )
        }
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
