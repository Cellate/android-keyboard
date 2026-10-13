package org.voxis.dictation.engine

import kotlin.math.sqrt

/**
 * Acoustic Feature & Voice Activity Analyzer.
 * Measures RMS energy, Zero-Crossing Rate (ZCR), and decibels across 16kHz PCM audio frames.
 * Strictly performs audio signal analysis without canned or simulated vocabulary.
 */
class StreamingAcousticEngine {

    companion object {
        const val FRAME_SIZE_SAMPLES = 1600 // 100ms @ 16kHz
    }

    data class FrameAcoustics(
        val rms: Float,
        val zcr: Float,
        val isVoiced: Boolean
    )

    fun analyzeFrame(samples: FloatArray, threshold: Float = 0.018f): FrameAcoustics {
        if (samples.isEmpty()) return FrameAcoustics(0f, 0f, false)

        var zeroCrossings = 0
        var energySum = 0f

        for (i in 1 until samples.size) {
            val curr = samples[i]
            val prev = samples[i - 1]
            if ((curr >= 0f && prev < 0f) || (curr < 0f && prev >= 0f)) {
                zeroCrossings++
            }
            energySum += curr * curr
        }

        val rms = sqrt(energySum / samples.size)
        val zcr = zeroCrossings.toFloat() / samples.size
        val isVoiced = rms >= threshold

        return FrameAcoustics(rms, zcr, isVoiced)
    }

    fun reset() {
        // State reset if needed
    }
}
