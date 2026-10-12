package org.voxis.dictation.engine

data class TranscriptionResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 0.95f,
    val durationMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)
