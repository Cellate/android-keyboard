package org.voxis.dictation.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File

/**
 * High-performance, offline Vosk / Kaldi Speech Recognition Engine for Android.
 * Runs 100% locally on device CPU with zero cloud dependencies.
 */
class VoskEngine : Closeable {

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE = 16000.0f
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    val isModelLoaded: Boolean
        get() = model != null && recognizer != null

    /**
     * Initializes the Vosk model from a local unzipped directory on disk.
     */
    suspend fun loadModel(modelDirectoryPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()

            val dir = File(modelDirectoryPath)
            if (!dir.exists() || !dir.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("Vosk model path is not a valid directory: $modelDirectoryPath"))
            }

            Log.i(TAG, "Loading Vosk acoustic model from: ${dir.absolutePath}")
            val loadedModel = Model(dir.absolutePath)
            val newRecognizer = Recognizer(loadedModel, SAMPLE_RATE)

            model = loadedModel
            recognizer = newRecognizer

            Log.i(TAG, "Vosk offline speech recognition model loaded successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model: ${e.message}", e)
            close()
            Result.failure(e)
        }
    }

    /**
     * Ingests 16kHz float PCM audio samples [-1.0, 1.0] in real time.
     * Returns partial recognized text while speaking, or finalized text on phrase boundaries.
     */
    fun processFrame(samples: FloatArray): Pair<String, Boolean> {
        val rec = recognizer ?: return Pair("", false)
        if (samples.isEmpty()) return Pair("", false)

        val shortSamples = ShortArray(samples.size)
        for (i in samples.indices) {
            shortSamples[i] = (samples[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }

        val isFinal = rec.acceptWaveForm(shortSamples, shortSamples.size)
        return if (isFinal) {
            val json = rec.result
            val text = parseTextFromJson(json)
            Pair(text, true)
        } else {
            val json = rec.partialResult
            val partial = parsePartialFromJson(json)
            Pair(partial, false)
        }
    }

    /**
     * Ingests complete batch audio buffer for Mode 1 (Record-then-Process).
     */
    suspend fun processBatch(samples: FloatArray): String = withContext(Dispatchers.Default) {
        val rec = recognizer ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""

        rec.reset()

        val chunkSize = 4000
        var i = 0
        while (i < samples.size) {
            val end = (i + chunkSize).coerceAtMost(samples.size)
            val count = end - i
            val shortBuffer = ShortArray(count)
            for (j in 0 until count) {
                shortBuffer[j] = (samples[i + j] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            }
            rec.acceptWaveForm(shortBuffer, count)
            i += chunkSize
        }

        val finalJson = rec.finalResult
        rec.reset()
        parseTextFromJson(finalJson)
    }

    fun getFinalResult(): String {
        val rec = recognizer ?: return ""
        val json = rec.finalResult
        return parseTextFromJson(json)
    }

    fun reset() {
        recognizer?.reset()
    }

    override fun close() {
        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null

        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
    }

    private fun parseTextFromJson(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            obj.optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parsePartialFromJson(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            obj.optString("partial", "").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
