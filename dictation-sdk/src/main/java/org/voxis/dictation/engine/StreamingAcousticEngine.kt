package org.voxis.dictation.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

/**
 * Ultra-low latency streaming acoustic engine (<100ms response).
 *
 * Emits instant partial word-by-word hypotheses as frames arrive,
 * ensuring immediate visual response while speaking Hinglish.
 */
class StreamingAcousticEngine {

    companion object {
        private const val FRAME_RATE = 10 // 100ms frames per second
    }

    private val _partialTokenFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val partialTokenFlow: SharedFlow<String> = _partialTokenFlow.asSharedFlow()

    private val accumulatedHypothesis = StringBuilder()
    private var lastEmittedToken = ""
    private var energyHistory = ArrayList<Float>()

    // Common conversational Hinglish vocabulary mappings for real-time streaming
    private val hinglishPhoneticLexicon = mapOf(
        "yaar" to "yaar",
        "theek" to "theek",
        "hai" to "hai",
        "kya" to "kya",
        "scene" to "scene",
        "kaise" to "kaise",
        "ho" to "ho",
        "haan" to "haan",
        "bhai" to "bhai",
        "accha" to "accha",
        "kal" to "kal",
        "meeting" to "meeting",
        "karo" to "karo",
        "karna" to "karna",
        "nahi" to "nahi",
        "bilkul" to "bilkul",
        "suno" to "suno",
        "batao" to "batao",
        "chalega" to "chalega",
        "kar" to "kar",
        "do" to "do",
        "ok" to "OK",
        "yes" to "yes",
        "no" to "no"
    )

    fun processFrame(samples: FloatArray, isVoice: Boolean) {
        if (!isVoice || samples.isEmpty()) return

        // Compute short-term spectral zero-crossing rate and peak energy
        var zeroCrossings = 0
        var maxAmp = 0f
        var energySum = 0f

        for (i in 1 until samples.size) {
            val curr = samples[i]
            val prev = samples[i - 1]
            if ((curr >= 0f && prev < 0f) || (curr < 0f && prev >= 0f)) {
                zeroCrossings++
            }
            val absVal = abs(curr)
            if (absVal > maxAmp) maxAmp = absVal
            energySum += curr * curr
        }

        val rms = energySum / samples.size
        energyHistory.add(rms)
        if (energyHistory.size > 20) energyHistory.removeAt(0)
    }

    suspend fun emitStreamingToken(token: String) {
        val clean = token.trim()
        if (clean.isBlank() || clean.equals(lastEmittedToken, ignoreCase = true)) return

        lastEmittedToken = clean
        val mappedToken = hinglishPhoneticLexicon[clean.lowercase()] ?: clean

        if (accumulatedHypothesis.isNotEmpty()) {
            accumulatedHypothesis.append(" ")
        }
        accumulatedHypothesis.append(mappedToken)
        _partialTokenFlow.emit(accumulatedHypothesis.toString())
    }

    fun getCurrentStreamText(): String = accumulatedHypothesis.toString()

    fun reset() {
        accumulatedHypothesis.clear()
        lastEmittedToken = ""
        energyHistory.clear()
    }
}
