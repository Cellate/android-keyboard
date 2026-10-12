package org.voxis.dictation.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

class AudioRecordStreamer {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val _chunkFlow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
    val chunkFlow: SharedFlow<AudioChunk> = _chunkFlow.asSharedFlow()

    private val _isRecordingFlow = MutableSharedFlow<Boolean>(replay = 1)
    val isRecordingFlow: SharedFlow<Boolean> = _isRecordingFlow.asSharedFlow()

    @Volatile
    var isRecording: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(scope: CoroutineScope): Result<Unit> {
        if (isRecording) {
            return Result.success(Unit)
        }

        val bufferSize = AudioConfig.getMinBufferSize()

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioConfig.SAMPLE_RATE_HZ,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Failed to initialize AudioRecord: ${e.message}", e))
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("AudioRecord initialization failed (state not initialized)"))
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return Result.failure(IllegalStateException("Failed to start recording: ${e.message}", e))
        }

        audioRecord = record
        isRecording = true
        _isRecordingFlow.tryEmit(true)

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(AudioConfig.SAMPLES_PER_CHUNK)
            try {
                while (isActive && isRecording) {
                    val readSamples = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readSamples > 0) {
                        val samplesCopy = ShortArray(readSamples)
                        System.arraycopy(shortBuffer, 0, samplesCopy, 0, readSamples)

                        val rms = calculateRms(samplesCopy)
                        val db = calculateDecibels(rms)

                        _chunkFlow.emit(AudioChunk(samples = samplesCopy, length = readSamples, rmsDecibels = db))
                    } else if (readSamples < 0) {
                        break
                    }
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled
            } finally {
                stopInternal()
            }
        }

        return Result.success(Unit)
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!isRecording && audioRecord == null) return

        isRecording = false
        _isRecordingFlow.tryEmit(false)

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (_: Exception) {
            // Ignore teardown exceptions
        } finally {
            audioRecord = null
        }
    }

    private fun calculateRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    private fun calculateDecibels(rms: Float): Float {
        if (rms <= 0.0001f) return -60f
        val db = 20f * log10(rms / Short.MAX_VALUE)
        return db.coerceIn(-60f, 0f)
    }
}
