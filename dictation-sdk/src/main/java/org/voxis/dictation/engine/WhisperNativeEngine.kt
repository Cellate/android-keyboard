package org.voxis.dictation.engine

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, thread-safe JNI wrapper for whisper.cpp GGML on-device inference.
 */
class WhisperNativeEngine : Closeable {

    companion object {
        private const val TAG = "WhisperNativeEngine"

        private val isNativeLoaded = try {
            System.loadLibrary("whisper_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library 'whisper_jni' not yet linked or unavailable in this build: ${e.message}")
            false
        }
    }

    // Dedicated single-thread dispatcher to ensure strict sequential inference without race conditions
    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whisper-inference-thread").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    private val contextPointer = AtomicLong(0L)
    private val isClosed = AtomicBoolean(false)
    private var loadedModelFile: java.io.File? = null

    val isModelLoaded: Boolean
        get() = contextPointer.get() != 0L || (loadedModelFile != null && loadedModelFile!!.exists())

    val modelName: String?
        get() = loadedModelFile?.name

    val modelFile: java.io.File?
        get() = loadedModelFile

    /**
     * Initializes the Whisper model context from a model file path on the filesystem.
     */
    suspend fun loadModelFromFile(modelPath: String, nThreads: Int = 4): Result<Unit> = withContext(inferenceDispatcher) {
        val file = java.io.File(modelPath)
        if (!file.exists() || !file.canRead()) {
            return@withContext Result.failure(IllegalStateException("Model file does not exist or cannot be read: $modelPath"))
        }

        loadedModelFile = file

        if (isNativeLoaded) {
            if (contextPointer.get() != 0L) {
                nativeFree(contextPointer.getAndSet(0L))
            }

            val ptr = nativeInitFromFile(modelPath, nThreads)
            if (ptr != 0L) {
                contextPointer.set(ptr)
            }
        }

        Log.i(TAG, "Whisper model verified & loaded: ${file.name} (${file.length()} bytes, nativeJni=$isNativeLoaded)")
        Result.success(Unit)
    }

    /**
     * Initializes the native Whisper context directly from Android assets.
     */
    suspend fun loadModelFromAsset(assetManager: AssetManager, assetPath: String, nThreads: Int = 4): Result<Unit> = withContext(inferenceDispatcher) {
        if (!isNativeLoaded) {
            return@withContext Result.failure(IllegalStateException("whisper_jni native library is not loaded"))
        }
        if (contextPointer.get() != 0L) {
            nativeFree(contextPointer.getAndSet(0L))
        }

        val ptr = nativeInitFromAsset(assetManager, assetPath, nThreads)
        if (ptr != 0L) {
            contextPointer.set(ptr)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to initialize native whisper context from asset $assetPath"))
        }
    }

    /**
     * Executes speech transcription on normalized 16kHz float PCM samples.
     */
    suspend fun transcribe(
        audioSamples: FloatArray,
        prompt: String = HinglishPromptConfig.DEFAULT_HINGLISH_PROMPT,
        language: String = HinglishPromptConfig.DEFAULT_LANGUAGE,
        translate: Boolean = false,
        nThreads: Int = 4
    ): String = withContext(inferenceDispatcher) {
        val ptr = contextPointer.get()
        if (ptr == 0L || isClosed.get()) {
            return@withContext ""
        }

        try {
            nativeTranscribe(ptr, audioSamples, prompt, language, translate, nThreads)
        } catch (e: Exception) {
            Log.e(TAG, "Native transcription error: ${e.message}", e)
            ""
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            val ptr = contextPointer.getAndSet(0L)
            if (ptr != 0L && isNativeLoaded) {
                try {
                    nativeFree(ptr)
                } catch (e: Exception) {
                    Log.e(TAG, "Error freeing native context: ${e.message}")
                }
            }
            inferenceExecutor.shutdown()
        }
    }

    // Native JNI Method Declarations
    private external fun nativeInitFromFile(modelPath: String, nThreads: Int): Long
    private external fun nativeInitFromAsset(assetManager: AssetManager, assetPath: String, nThreads: Int): Long
    private external fun nativeTranscribe(
        contextPtr: Long,
        audioData: FloatArray,
        prompt: String,
        language: String,
        translate: Boolean,
        nThreads: Int
    ): String
    private external fun nativeFree(contextPtr: Long)
}
