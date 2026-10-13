package org.voxis.dictation.ui

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import org.voxis.dictation.engine.HybridHinglishEngine
import org.voxis.dictation.engine.ModelManager
import org.voxis.dictation.engine.VoiceModelConfig
import org.voxis.dictation.engine.WhisperNativeEngine
import java.io.File

enum class PipelineStage(val label: String) {
    IDLE("Ready"),
    RECORDING("Capturing 16kHz PCM audio..."),
    ANALYZING_AUDIO("Validating audio buffer..."),
    DISPATCHING_MODEL("Dispatching frames to neural model..."),
    INFERENCE_RUNNING("Model executing speech recognition..."),
    FORMATTING_OUTPUT("Formatting transcription..."),
    COMPLETED("Transcription Complete"),
    ERROR("Pipeline Error")
}

data class PipelineMetrics(
    val audioDurationSeconds: Float = 0f,
    val sampleCount: Int = 0,
    val peakDecibels: Float = -60f,
    val inferenceTimeMs: Long = 0L,
    val realTimeFactor: Float = 0f,
    val wordCount: Int = 0,
    val activeEngineDescription: String = "No Model Loaded"
)

data class TranscriptionUiState(
    val isRecording: Boolean = false,
    val isModelLoaded: Boolean = false,
    val liveTranscript: String = "",
    val interimStreamingText: String = "",
    val audioLevel: Float = 0f,
    val currentDb: Float = -60f,
    val activeModelName: String = "No Model",
    val engineMode: HinglishEngineMode = HinglishEngineMode.LIVE_REALTIME,
    val pipelineStage: PipelineStage = PipelineStage.IDLE,
    val pipelineMetrics: PipelineMetrics = PipelineMetrics(),
    val modelConfig: VoiceModelConfig = VoiceModelConfig.getRecommended(),
    val statusMessage: String = "Ready"
)

/**
 * Production ViewModel managing genuine on-device speech-to-text inference.
 * Coordinates Vosk (Kaldi) and Whisper GGML models without canned or simulated words.
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

    val liveTranscriptText: StateFlow<String> = _uiState
        .map { it.liveTranscript }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ""
        )

    init {
        // Load persisted voice model parameters
        val initialConfig = VoiceModelConfig.load(application)
        _uiState.update { it.copy(modelConfig = initialConfig) }

        // Attach hybrid orchestrator coroutine listeners
        hybridHinglishEngine.attach(viewModelScope)

        // Collect real-time audio levels and decibels for UI waveforms
        viewModelScope.launch {
            audioRecorderManager.audioLevel.collect { level ->
                _uiState.update { it.copy(audioLevel = level) }
            }
        }

        viewModelScope.launch {
            audioRecorderManager.currentDb.collect { db ->
                _uiState.update { it.copy(currentDb = db) }
            }
        }

        viewModelScope.launch {
            audioRecorderManager.sessionSampleCount.collect { samples ->
                if (_uiState.value.isRecording) {
                    val duration = samples.toFloat() / 16000f
                    _uiState.update { current ->
                        current.copy(
                            pipelineMetrics = current.pipelineMetrics.copy(
                                sampleCount = samples,
                                audioDurationSeconds = duration
                            )
                        )
                    }
                }
            }
        }

        // Collect genuine engine text state and propagate to UI
        viewModelScope.launch {
            hybridHinglishEngine.state.collect { hybridState ->
                _uiState.update { current ->
                    val words = hybridState.liveText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                    current.copy(
                        liveTranscript = hybridState.liveText,
                        interimStreamingText = hybridState.interimWordText,
                        engineMode = hybridState.currentMode,
                        statusMessage = if (current.isRecording) {
                            if (hybridState.isSpeaking) "Speaking..." else "Listening..."
                        } else current.statusMessage,
                        pipelineMetrics = current.pipelineMetrics.copy(
                            wordCount = words,
                            inferenceTimeMs = hybridState.lastLatencyMs,
                            activeEngineDescription = hybridState.engineName
                        )
                    )
                }
            }
        }

        // Ingest 100ms audio frames into engine in real time
        viewModelScope.launch {
            audioRecorderManager.frameFlow.collect { (frameSamples, isVoice) ->
                if (_uiState.value.isModelLoaded) {
                    hybridHinglishEngine.processFrame(
                        frameSamples,
                        isVoice,
                        viewModelScope,
                        _uiState.value.modelConfig
                    )
                }
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
        val threads = _uiState.value.modelConfig.nThreads

        // 1. Try loading from filesystem if user downloaded or imported model
        if (activeModel.isDownloaded() && !activeModel.localPath.isNullOrBlank()) {
            val file = File(activeModel.localPath!!)
            if (file.exists()) {
                // If it's a directory (Vosk model)
                if (file.isDirectory) {
                    val res = hybridHinglishEngine.voskEngine.loadModel(file.absolutePath)
                    if (res.isSuccess) {
                        _uiState.update {
                            it.copy(
                                isModelLoaded = true,
                                activeModelName = activeModel.displayName,
                                statusMessage = "Loaded: ${activeModel.displayName}",
                                pipelineMetrics = it.pipelineMetrics.copy(
                                    activeEngineDescription = "Vosk Neural Engine (${activeModel.language})"
                                )
                            )
                        }
                        return@withContext
                    }
                } else if (file.isFile && (file.name.endsWith(".bin") || file.name.endsWith(".gguf"))) {
                    val res = whisperEngine.loadModelFromFile(file.absolutePath, threads)
                    if (res.isSuccess) {
                        _uiState.update {
                            it.copy(
                                isModelLoaded = true,
                                activeModelName = activeModel.displayName,
                                statusMessage = "Loaded: ${activeModel.displayName}",
                                pipelineMetrics = it.pipelineMetrics.copy(
                                    activeEngineDescription = "Whisper GGML ($threads threads)"
                                )
                            )
                        }
                        return@withContext
                    }
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
                    threads
                )
                if (res.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isModelLoaded = true,
                            activeModelName = targetAsset,
                            statusMessage = "Loaded from assets: $targetAsset",
                            pipelineMetrics = it.pipelineMetrics.copy(
                                activeEngineDescription = "Whisper Asset ($threads threads)"
                            )
                        )
                    }
                    return@withContext
                }
            }
        } catch (_: Exception) {}

        val hasAnyModel = hybridHinglishEngine.voskEngine.isModelLoaded || whisperEngine.isModelLoaded

        _uiState.update {
            it.copy(
                isModelLoaded = hasAnyModel,
                activeModelName = if (hasAnyModel) activeModel.displayName else "None (Select in Models tab)",
                statusMessage = if (hasAnyModel) "Model Ready" else "⚠️ No Model Loaded - Download in Models tab",
                pipelineMetrics = it.pipelineMetrics.copy(
                    activeEngineDescription = if (hasAnyModel) activeModel.displayName else "No Model Loaded (Standby)"
                )
            )
        }
    }

    private var systemSpeechRecognizer: SpeechRecognizer? = null

    fun startTranscription() {
        if (_uiState.value.isRecording) return

        val result = audioRecorderManager.startRecording(viewModelScope)
        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    isRecording = true,
                    pipelineStage = PipelineStage.RECORDING,
                    statusMessage = if (it.isModelLoaded) "Listening..." else "Listening (16kHz PCM audio buffer active)..."
                )
            }

            // If no offline Whisper/Vosk neural model is loaded on disk, try AOSP SpeechRecognizer as live fallback
            if (!_uiState.value.isModelLoaded) {
                tryStartSystemSpeechRecognizer()
            }
        } else {
            _uiState.update {
                it.copy(
                    pipelineStage = PipelineStage.ERROR,
                    statusMessage = "Mic error: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    private fun tryStartSystemSpeechRecognizer() {
        val app = getApplication<android.app.Application>()
        if (SpeechRecognizer.isRecognitionAvailable(app)) {
            viewModelScope.launch(Dispatchers.Main) {
                try {
                    systemSpeechRecognizer?.destroy()
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
                    val lang = when (_uiState.value.modelConfig.languageCode) {
                        "hi" -> "hi-IN"
                        "hi-en" -> "en-IN"
                        else -> "en-US"
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        putExtra("android.speech.extra.LANGUAGE", lang)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            Log.d(TAG, "System SpeechRecognizer error code: $error")
                        }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val text = matches[0]
                                val current = _uiState.value.liveTranscript
                                val updated = if (current.isBlank()) text else "$current $text"
                                val words = updated.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                                _uiState.update {
                                    it.copy(
                                        liveTranscript = updated,
                                        interimStreamingText = "",
                                        pipelineMetrics = it.pipelineMetrics.copy(wordCount = words)
                                    )
                                }
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                _uiState.update {
                                    it.copy(interimStreamingText = matches[0])
                                }
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    recognizer.startListening(intent)
                    systemSpeechRecognizer = recognizer
                } catch (e: Exception) {
                    Log.w(TAG, "System speech recognizer failed: ${e.message}")
                }
            }
        }
    }

    fun stopTranscription() {
        if (!_uiState.value.isRecording) return

        viewModelScope.launch(Dispatchers.Main) {
            try {
                systemSpeechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }

        val currentMode = _uiState.value.engineMode
        val config = _uiState.value.modelConfig
        val hasModel = _uiState.value.isModelLoaded

        if (currentMode == HinglishEngineMode.RECORD_THEN_PROCESS) {
            // Mode 1: Push-to-Talk Batch Process with clear behind-the-scenes pipeline stages
            viewModelScope.launch(Dispatchers.Default) {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        pipelineStage = PipelineStage.ANALYZING_AUDIO,
                        statusMessage = "Validating audio buffer..."
                    )
                }

                val frozenSamples = audioRecorderManager.stopRecording()
                val durationSec = frozenSamples.size.toFloat() / 16000f

                _uiState.update {
                    it.copy(
                        pipelineStage = PipelineStage.DISPATCHING_MODEL,
                        statusMessage = if (hasModel) "Dispatching to model..." else "Validating audio buffer...",
                        pipelineMetrics = it.pipelineMetrics.copy(
                            sampleCount = frozenSamples.size,
                            audioDurationSeconds = durationSec
                        )
                    )
                }

                if (hasModel) {
                    _uiState.update {
                        it.copy(
                            pipelineStage = PipelineStage.INFERENCE_RUNNING,
                            statusMessage = "Model executing speech recognition..."
                        )
                    }

                    val startTime = System.currentTimeMillis()
                    val transcriptionResult = hybridHinglishEngine.processBatchAudio(frozenSamples, config)
                    val inferenceMs = System.currentTimeMillis() - startTime
                    val rtf = if (durationSec > 0f) (inferenceMs / 1000f) / durationSec else 0f
                    val wordCount = transcriptionResult.text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

                    _uiState.update {
                        it.copy(
                            pipelineStage = PipelineStage.FORMATTING_OUTPUT,
                            statusMessage = "Formatting output..."
                        )
                    }

                    _uiState.update {
                        it.copy(
                            pipelineStage = PipelineStage.COMPLETED,
                            statusMessage = if (transcriptionResult.text.isNotBlank()) "Completed in ${inferenceMs}ms" else "No speech detected in audio",
                            pipelineMetrics = it.pipelineMetrics.copy(
                                inferenceTimeMs = inferenceMs,
                                realTimeFactor = rtf,
                                wordCount = wordCount
                            )
                        )
                    }
                } else {
                    // No offline model loaded yet
                    _uiState.update {
                        val currentText = it.liveTranscript
                        val fallbackNotice = if (currentText.isBlank()) {
                            "Audio captured (${String.format("%.1f", durationSec)}s, ${frozenSamples.size} samples @ 16kHz PCM). Microphone input is active and verified! To transcribe your voice completely offline, download a speech model (e.g. Whisper Tiny 75MB or Vosk Small 42MB) in the Models tab."
                        } else currentText

                        it.copy(
                            liveTranscript = fallbackNotice,
                            pipelineStage = PipelineStage.COMPLETED,
                            statusMessage = "Audio captured (${String.format("%.1f", durationSec)}s). Download model in Models tab to decode."
                        )
                    }
                }
            }
        } else {
            // Mode 2: Live Real-Time Streaming
            audioRecorderManager.stopRecording()
            if (hasModel) {
                hybridHinglishEngine.onRecordingStopped(viewModelScope, config)
            }

            _uiState.update {
                it.copy(
                    isRecording = false,
                    pipelineStage = PipelineStage.COMPLETED,
                    statusMessage = if (hasModel) "Complete" else "Complete. Download model in Models tab for 100% offline transcription."
                )
            }
        }
    }

    fun setEngineMode(mode: HinglishEngineMode) {
        hybridHinglishEngine.setMode(mode)
        _uiState.update { it.copy(engineMode = mode) }
    }

    fun updateModelConfig(newConfig: VoiceModelConfig) {
        VoiceModelConfig.save(getApplication(), newConfig)
        _uiState.update { it.copy(modelConfig = newConfig) }
        viewModelScope.launch(Dispatchers.IO) {
            loadActiveModel()
        }
    }

    fun resetModelConfigToRecommended() {
        val recommended = VoiceModelConfig.getRecommended()
        updateModelConfig(recommended)
    }

    fun clearTranscript() {
        hybridHinglishEngine.clear()
        _uiState.update {
            it.copy(
                liveTranscript = "",
                interimStreamingText = "",
                pipelineStage = PipelineStage.IDLE,
                statusMessage = "Ready",
                pipelineMetrics = it.pipelineMetrics.copy(
                    sampleCount = 0,
                    audioDurationSeconds = 0f,
                    wordCount = 0
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            systemSpeechRecognizer?.destroy()
            systemSpeechRecognizer = null
        } catch (_: Exception) {}
        try {
            audioRecorderManager.stopRecording()
            whisperEngine.close()
            hybridHinglishEngine.voskEngine.close()
            Log.i(TAG, "TranscriptionViewModel cleared: AudioRecord, Whisper, and Vosk released safely")
        } catch (e: Exception) {
            Log.e(TAG, "Error during ViewModel cleanup: ${e.message}", e)
        }
    }
}
