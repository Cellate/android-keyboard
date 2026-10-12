package org.voxis.dictation.vad

import org.voxis.dictation.audio.AudioChunk

class VoiceActivityDetector(
    private val speechThresholdDb: Float = -48.0f,
    private val silenceThresholdMs: Long = 1400L,
    private val minSpeechDurationMs: Long = 150L
) {
    enum class VadEvent {
        SPEECH_START,
        SPEECH_CONTINUING,
        SPEECH_END,
        SILENCE
    }

    private var isSpeaking = false
    private var speechStartTimeMs: Long = 0L
    private var lastSpeechTimeMs: Long = 0L
    private var adaptiveNoiseFloorDb: Float = -56.0f

    @Synchronized
    fun processChunk(chunk: AudioChunk): VadEvent {
        val now = chunk.timestampMs
        val db = chunk.rmsDecibels

        // Adapt noise floor slowly over time during silence
        if (!isSpeaking) {
            adaptiveNoiseFloorDb = 0.96f * adaptiveNoiseFloorDb + 0.04f * db
        }

        // More sensitive speech threshold: either fixed threshold or 3dB above noise floor
        val effectiveThreshold = minOf(speechThresholdDb, adaptiveNoiseFloorDb + 4f).coerceAtLeast(-52f)
        val hasEnergy = db > effectiveThreshold

        return if (hasEnergy) {
            lastSpeechTimeMs = now
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = now
                VadEvent.SPEECH_START
            } else {
                VadEvent.SPEECH_CONTINUING
            }
        } else {
            if (isSpeaking) {
                val silenceDuration = now - lastSpeechTimeMs
                val totalSpeechDuration = now - speechStartTimeMs

                if (silenceDuration >= silenceThresholdMs && totalSpeechDuration >= minSpeechDurationMs) {
                    isSpeaking = false
                    VadEvent.SPEECH_END
                } else {
                    VadEvent.SPEECH_CONTINUING
                }
            } else {
                VadEvent.SILENCE
            }
        }
    }

    @Synchronized
    fun reset() {
        isSpeaking = false
        speechStartTimeMs = 0L
        lastSpeechTimeMs = 0L
        adaptiveNoiseFloorDb = -56.0f
    }
}
