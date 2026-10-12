package org.voxis.dictation.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.voxis.dictation.audio.AudioRecorderManager
import org.voxis.dictation.engine.HinglishPromptConfig
import org.voxis.dictation.engine.ModelManager
import org.voxis.dictation.engine.WhisperNativeEngine
import java.io.File

data class TranscriptionUiState(
    val isRecording: Boolean = false,
    val isModelLoaded: Boolean = false,
    val liveTranscript: String = "",
    val audioLevel: Float = 0f,
    val activeModelName: String = "No Model",
    val statusMessage: String = "Ready"
)

/**
 * Production ViewModel managing the complete on-device transcription lifecycle,
 * streaming audio chunks into whisper.cpp JNI without UI jank or ANRs.
 */
class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TranscriptionViewModel"
    }

    private val audioRecorderManager = AudioRecorderManager()
    private val whisperEngine = WhisperNativeEngine()

    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    // Dedicated StateFlow<String> returning live transcription results directly to the UI
    val liveTranscriptText: StateFlow<String> = _uiState
        .map { it.liveTranscript }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ""
        )

    init {
        // Collect real-time audio levels for UI waveforms
        viewModelScope.launch {
            audioRecorderManager.audioLevel.collect { level ->
                _uiState.update { it.copy(audioLevel = level) }
            }
        }

        // Collect and process chunked audio buffers through whisper.cpp inference
        viewModelScope.launch {
            audioRecorderManager.audioChunkFlow.collect { audioFloats ->
                processAudioChunk(audioFloats)
            }
        }

        // Initialize active model from storage or assets on background thread
        viewModelScope.launch(Dispatchers.IO) {
            loadActiveModel()
        }
    }

    suspend fun loadActiveModel() = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val activeModel = ModelManager.getActiveModel(context)

        // 1. Try loading from filesystem if user downloaded or imported model
        if (activeModel.isDownloaded() && !activeModel.localPath.isNullOrBlank()) {
            val file = File(activeModel.localPath!!)
            if (file.exists()) {
                val res = whisperEngine.loadModelFromFile(file.absolutePath, HinglishPromptConfig.RECOMMENDED_THREADS)
                if (res.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isModelLoaded = true,
                            activeModelName = activeModel.displayName,
                            statusMessage = "Loaded: ${activeModel.displayName}"
                        )
                    }
                    return@withContext
                }
            }
        }

        // 2. Try loading bundled asset if present in assets/models/
        try {
            val assetList = context.assets.list("models") ?: emptyArray()
            val targetAsset = assetList.firstOrNull { it.endsWith(".bin") || it.endsWith(".gguf") }
            if (targetAsset != null) {
                val res = whisperEngine.loadModelFromAsset(
                    context.assets,
                    "models/$targetAsset",
                    HinglishPromptConfig.RECOMMENDED_THREADS
                )
                if (res.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isModelLoaded = true,
                            activeModelName = targetAsset,
                            statusMessage = "Loaded from assets: $targetAsset"
                        )
                    }
                    return@withContext
                }
            }
        } catch (_: Exception) {}

        _uiState.update {
            it.copy(
                isModelLoaded = whisperEngine.isModelLoaded,
                activeModelName = if (whisperEngine.isModelLoaded) activeModel.displayName else "None (Download in Models tab)",
                statusMessage = if (whisperEngine.isModelLoaded) "Model Ready" else "No model file found"
            )
        }
    }

    fun startTranscription() {
        if (_uiState.value.isRecording) return

        val result = audioRecorderManager.startRecording(viewModelScope)
        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    isRecording = true,
                    statusMessage = "Listening..."
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    statusMessage = "Mic error: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun stopTranscription() {
        if (!_uiState.value.isRecording) return

        val remainingAudio = audioRecorderManager.stopRecording()
        _uiState.update {
            it.copy(
                isRecording = false,
                statusMessage = "Complete"
            )
        }

        // Process final remaining audio buffer
        remainingAudio?.let { chunk ->
            viewModelScope.launch {
                processAudioChunk(chunk)
            }
        }
    }

    private suspend fun processAudioChunk(audioFloats: FloatArray) = withContext(Dispatchers.Default) {
        if (audioFloats.isEmpty()) return@withContext

        _uiState.update { it.copy(statusMessage = "Transcribing...") }

        // Execute inference on dedicated native thread with Hinglish steering parameters
        val recognizedText = whisperEngine.transcribe(
            audioSamples = audioFloats,
            prompt = HinglishPromptConfig.DEFAULT_HINGLISH_PROMPT,
            language = HinglishPromptConfig.DEFAULT_LANGUAGE,
            translate = HinglishPromptConfig.DEFAULT_TRANSLATE,
            nThreads = HinglishPromptConfig.RECOMMENDED_THREADS
        ).trim()

        if (recognizedText.isNotBlank()) {
            _uiState.update { current ->
                val updatedTranscript = if (current.liveTranscript.isBlank()) {
                    recognizedText
                } else {
                    "${current.liveTranscript} $recognizedText"
                }
                current.copy(
                    liveTranscript = updatedTranscript,
                    statusMessage = if (current.isRecording) "Listening..." else "Complete"
                )
            }
        } else {
            _uiState.update { current ->
                current.copy(statusMessage = if (current.isRecording) "Listening..." else "Ready")
            }
        }
    }

    fun clearTranscript() {
        _uiState.update { it.copy(liveTranscript = "") }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            audioRecorderManager.stopRecording()
            whisperEngine.close()
            Log.i(TAG, "TranscriptionViewModel cleared: AudioRecord and WhisperNativeEngine context released safely")
        } catch (e: Exception) {
            Log.e(TAG, "Error during ViewModel cleanup: ${e.message}", e)
        }
    }
}
