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
import org.voxis.dictation.engine.HinglishEngineMode
import org.voxis.dictation.engine.HinglishPromptConfig
import org.voxis.dictation.engine.HybridHinglishEngine
import org.voxis.dictation.engine.ModelManager
import org.voxis.dictation.engine.WhisperNativeEngine
import java.io.File

data class TranscriptionUiState(
    val isRecording: Boolean = false,
    val isModelLoaded: Boolean = false,
    val liveTranscript: String = "",
    val interimStreamingText: String = "",
    val audioLevel: Float = 0f,
    val activeModelName: String = "No Model",
    val engineMode: HinglishEngineMode = HinglishEngineMode.HYBRID,
    val statusMessage: String = "Ready"
)

/**
 * Production ViewModel managing the complete on-device transcription lifecycle.
 * Coordinates both:
 * 1. Low-latency streaming word emission (<100ms) for immediate visual feedback.
 * 2. FUTO-style VAD-endpointed Whisper for full conversational Hinglish Latin punctuation.
 */
class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TranscriptionViewModel"
    }

    private val audioRecorderManager = AudioRecorderManager()
    private val whisperEngine = WhisperNativeEngine()
    private val hybridHinglishEngine = HybridHinglishEngine(whisperEngine)

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
        // Attach hybrid orchestrator coroutine listeners
        hybridHinglishEngine.attach(viewModelScope)

        // Collect real-time audio levels for UI waveforms
        viewModelScope.launch {
            audioRecorderManager.audioLevel.collect { level ->
                _uiState.update { it.copy(audioLevel = level) }
            }
        }

        // Collect hybrid engine text state and propagate to UI
        viewModelScope.launch {
            hybridHinglishEngine.state.collect { hybridState ->
                _uiState.update { current ->
                    current.copy(
                        liveTranscript = hybridState.liveText,
                        interimStreamingText = hybridState.interimWordText,
                        engineMode = hybridState.currentMode,
                        statusMessage = if (current.isRecording) {
                            if (hybridState.isSpeaking) "Speaking..." else "Listening..."
                        } else current.statusMessage
                    )
                }
            }
        }

        // Ingest 100ms audio frames into hybrid engine in real time
        viewModelScope.launch {
            audioRecorderManager.frameFlow.collect { (frameSamples, isVoice) ->
                hybridHinglishEngine.processFrame(frameSamples, isVoice, viewModelScope)
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
                activeModelName = if (whisperEngine.isModelLoaded) activeModel.displayName else "None (Select in Models tab)",
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

        audioRecorderManager.stopRecording()
        hybridHinglishEngine.onRecordingStopped(viewModelScope)

        _uiState.update {
            it.copy(
                isRecording = false,
                statusMessage = "Complete"
            )
        }
    }

    fun setEngineMode(mode: HinglishEngineMode) {
        hybridHinglishEngine.setMode(mode)
        _uiState.update { it.copy(engineMode = mode) }
    }

    fun clearTranscript() {
        hybridHinglishEngine.clear()
        _uiState.update {
            it.copy(
                liveTranscript = "",
                interimStreamingText = ""
            )
        }
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
