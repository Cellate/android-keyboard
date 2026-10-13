package org.voxis.dictation.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.voxis.dictation.engine.HinglishPromptConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Production-ready Audio Capture & Normalization Pipeline targeting Android 13+ (API 33+).
 * Captures 16000Hz mono 16-bit PCM, converts directly to [-1.0, 1.0] float buffers,
 * preserves complete session audio for Batch processing, and streams 100ms frames for Live mode.
 */
class AudioRecorderManager {

    companion object {
        private const val TAG = "AudioRecorderManager"
        const val SAMPLE_RATE: Int = HinglishPromptConfig.SAMPLE_RATE_HZ
        const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT

        // Silence / Voice Activity Threshold (-42 dBFS)
        private const val SILENCE_THRESHOLD_DB: Float = -42.0f
        private const val SILENCE_TIMEOUT_MS: Long = 750L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // Flow of normalized float chunks ready for Whisper inference
    private val _audioChunkFlow = MutableSharedFlow<FloatArray>(extraBufferCapacity = 16)
    val audioChunkFlow: SharedFlow<FloatArray> = _audioChunkFlow.asSharedFlow()

    // Real-time 100ms frame flow (audioFloats, isVoiceActive) for near-zero latency streaming
    private val _frameFlow = MutableSharedFlow<Pair<FloatArray, Boolean>>(extraBufferCapacity = 32)
    val frameFlow: SharedFlow<Pair<FloatArray, Boolean>> = _frameFlow.asSharedFlow()

    // Real-time audio RMS level [0.0 .. 1.0] for UI visualizer
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Real-time audio Decibels for UI metrics
    private val _currentDb = MutableStateFlow(-60f)
    val currentDb: StateFlow<Float> = _currentDb.asStateFlow()

    // Total samples recorded in current active session
    private val _sessionSampleCount = MutableStateFlow(0)
    val sessionSampleCount: StateFlow<Int> = _sessionSampleCount.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Complete session buffer preserved for Mode 1 (Batch Record-then-Process)
    private val fullSessionBuffer = ArrayList<Float>(SAMPLE_RATE * 30) // Up to 30s session
    private val sessionLock = Any()

    // Rolling sample accumulator for chunk emission
    private val sampleAccumulator = ArrayList<Float>(HinglishPromptConfig.MAX_BUFFER_SAMPLES)
    private val accumulatorLock = Any()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(scope: CoroutineScope): Result<Unit> {
        if (isRunning.get()) {
            return Result.success(Unit)
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return Result.failure(IllegalStateException("Unable to query valid AudioRecord buffer size"))
        }

        val bufferSize = (minBufferSize * 4).coerceAtLeast(SAMPLE_RATE * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("AudioRecord creation failed: ${e.message}", e))
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("AudioRecord failed to initialize"))
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return Result.failure(IllegalStateException("Failed to begin AudioRecord streaming: ${e.message}", e))
        }

        audioRecord = record
        isRunning.set(true)
        _isRecording.value = true

        synchronized(sessionLock) {
            fullSessionBuffer.clear()
        }
        synchronized(accumulatorLock) {
            sampleAccumulator.clear()
        }
        _sessionSampleCount.value = 0

        recordingJob = scope.launch(Dispatchers.IO) {
            val readBuffer = ShortArray(1600) // 100ms chunks (1600 samples @ 16kHz)
            var lastVoiceActivityTimeMs = System.currentTimeMillis()
            var speechDetectedInSession = false

            try {
                while (isActive && isRunning.get()) {
                    val readSamples = record.read(readBuffer, 0, readBuffer.size)
                    if (readSamples <= 0) continue

                    // 1. Calculate RMS and dBFS
                    val rms = calculateRms(readBuffer, readSamples)
                    val db = calculateDecibels(rms)
                    _currentDb.value = db
                    val normalizedLevel = ((db + 60f) / 60f).coerceIn(0f, 1f)
                    _audioLevel.value = normalizedLevel

                    // 2. Normalization: convert PCM 16-bit to [-1.0f, 1.0f] floats
                    val normalizedFloats = FloatArray(readSamples)
                    for (i in 0 until readSamples) {
                        normalizedFloats[i] = readBuffer[i].toFloat() / 32768.0f
                    }

                    // Preserve into full session buffer for Mode 1
                    synchronized(sessionLock) {
                        for (sample in normalizedFloats) {
                            fullSessionBuffer.add(sample)
                        }
                        _sessionSampleCount.value = fullSessionBuffer.size
                    }

                    val now = System.currentTimeMillis()
                    val isVoice = db > SILENCE_THRESHOLD_DB
                    if (isVoice) {
                        lastVoiceActivityTimeMs = now
                        speechDetectedInSession = true
                    }

                    // Emit 100ms frame immediately for real-time streaming engines (Mode 2)
                    _frameFlow.emit(Pair(normalizedFloats, isVoice))

                    // 3. Accumulate samples for rolling chunks
                    var chunkToEmit: FloatArray? = null
                    synchronized(accumulatorLock) {
                        for (sample in normalizedFloats) {
                            sampleAccumulator.add(sample)
                        }

                        val currentCount = sampleAccumulator.size
                        val reachedTargetWindow = currentCount >= HinglishPromptConfig.TARGET_CHUNK_SAMPLES
                        val speechPauseDetected = speechDetectedInSession &&
                                (currentCount >= (SAMPLE_RATE * HinglishPromptConfig.MIN_CHUNK_DURATION_MS / 1000)) &&
                                ((now - lastVoiceActivityTimeMs) > SILENCE_TIMEOUT_MS)
                        val reachedMaxWindow = currentCount >= HinglishPromptConfig.MAX_BUFFER_SAMPLES

                        if (reachedTargetWindow || speechPauseDetected || reachedMaxWindow) {
                            chunkToEmit = sampleAccumulator.toFloatArray()
                            sampleAccumulator.clear()
                            speechDetectedInSession = false
                        }
                    }

                    chunkToEmit?.let { chunk ->
                        if (chunk.isNotEmpty()) {
                            _audioChunkFlow.emit(chunk)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Cooperative coroutine cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Exception during audio stream read loop: ${e.message}", e)
            } finally {
                stopInternal()
            }
        }

        return Result.success(Unit)
    }

    @Synchronized
    fun stopRecording(): FloatArray {
        stopInternal()

        // Return complete frozen audio buffer for Mode 1 batch processing
        return synchronized(sessionLock) {
            val fullArray = fullSessionBuffer.toFloatArray()
            fullSessionBuffer.clear()
            fullArray
        }
    }

    private fun stopInternal() {
        if (!isRunning.compareAndSet(true, false)) return

        _isRecording.value = false
        _audioLevel.value = 0f

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release exception: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    private fun calculateRms(samples: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val sample = samples[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    private fun calculateDecibels(rms: Float): Float {
        if (rms <= 0.0001f) return -60f
        val db = 20f * log10(rms / Short.MAX_VALUE)
        return db.coerceIn(-60f, 0f)
    }
}
