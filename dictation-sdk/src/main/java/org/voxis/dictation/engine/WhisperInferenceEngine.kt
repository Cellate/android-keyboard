package org.voxis.dictation.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.voxis.dictation.audio.AudioChunk
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

interface InferenceEngine {
    val model: SpeechModel
    val isInitialized: Boolean
    suspend fun initialize(): Result<Unit>
    suspend fun transcribeChunk(audioChunk: AudioChunk): TranscriptionResult?
    suspend fun finalizeTranscription(): TranscriptionResult?
    fun appendRecognizedToken(token: String)
    fun setRecognizedText(text: String)
    fun reset()
    fun release()
}

class WhisperInferenceEngine(
    override val model: SpeechModel = SpeechModel.TinyEnglish,
    private val formatter: TextStreamFormatter = TextStreamFormatter(),
    private val voskEngine: VoskEngine = VoskEngine()
) : InferenceEngine {

    private val initialized = AtomicBoolean(false)
    override val isInitialized: Boolean get() = initialized.get()

    val hasLoadedModel: Boolean get() = model.isDownloaded()

    private val sampleBuffer = mutableListOf<Float>()
    private val accumulatedText = StringBuilder()
    private var lastDecodedSegment = ""
    private var repetitionCount = 0

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            sampleBuffer.clear()
            accumulatedText.clear()
            lastDecodedSegment = ""
            repetitionCount = 0

            if (model.isDownloaded() && !model.localPath.isNullOrBlank()) {
                val file = File(model.localPath!!)
                if (file.isDirectory) {
                    voskEngine.loadModel(file.absolutePath)
                }
            }

            initialized.set(true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun transcribeChunk(audioChunk: AudioChunk): TranscriptionResult? = withContext(Dispatchers.Default) {
        if (!initialized.get()) return@withContext null

        synchronized(sampleBuffer) {
            for (i in 0 until audioChunk.length) {
                sampleBuffer.add(audioChunk.samples[i] / 32768.0f)
            }
        }

        val floatChunk = FloatArray(audioChunk.length) { i -> audioChunk.samples[i] / 32768.0f }

        if (voskEngine.isModelLoaded) {
            val (recText, isFinal) = voskEngine.processFrame(floatChunk)
            if (recText.isNotBlank()) {
                val combined = if (accumulatedText.isEmpty()) recText else "$accumulatedText $recText"
                if (isFinal) {
                    accumulatedText.clear()
                    accumulatedText.append(combined)
                }
                return@withContext TranscriptionResult(
                    text = combined,
                    isFinal = isFinal,
                    confidence = 0.95f,
                    durationMs = (sampleBuffer.size * 1000L) / 16000L
                )
            }
        }

        decodeStreamingSamples(isFinal = false)
    }

    override suspend fun finalizeTranscription(): TranscriptionResult? = withContext(Dispatchers.Default) {
        if (!initialized.get()) return@withContext null

        if (voskEngine.isModelLoaded && accumulatedText.isBlank()) {
            val finalVosk = voskEngine.getFinalResult()
            if (finalVosk.isNotBlank()) {
                accumulatedText.append(finalVosk)
            }
        }

        val finalResult = decodeStreamingSamples(isFinal = true)
        reset()
        finalResult
    }

    private fun decodeStreamingSamples(isFinal: Boolean): TranscriptionResult? {
        val raw = accumulatedText.toString().trim()
        if (raw.isEmpty()) return null

        val samplesCount = synchronized(sampleBuffer) { sampleBuffer.size }
        val durationMs = (samplesCount * 1000L) / 16000L
        val formatted = formatter.format(raw)

        return TranscriptionResult(
            text = formatted,
            isFinal = isFinal,
            confidence = 0.96f,
            durationMs = durationMs
        )
    }

    override fun appendRecognizedToken(token: String) {
        if (token.isBlank()) return

        if (token.equals(lastDecodedSegment, ignoreCase = true)) {
            repetitionCount++
            if (repetitionCount > 2) return
        } else {
            repetitionCount = 0
            lastDecodedSegment = token
        }

        if (accumulatedText.isNotEmpty()) {
            accumulatedText.append(" ")
        }
        accumulatedText.append(token)
    }

    override fun setRecognizedText(text: String) {
        accumulatedText.clear()
        accumulatedText.append(text.trim())
    }

    override fun reset() {
        synchronized(sampleBuffer) {
            sampleBuffer.clear()
        }
        accumulatedText.clear()
        lastDecodedSegment = ""
        repetitionCount = 0
        voskEngine.reset()
    }

    override fun release() {
        reset()
        voskEngine.close()
        initialized.set(false)
    }
}
