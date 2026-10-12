package org.voxis.dictation.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.voxis.dictation.audio.AudioRecordStreamer
import org.voxis.dictation.audio.AudioRouteManager
import org.voxis.dictation.engine.SpeechModel
import org.voxis.dictation.engine.TextStreamFormatter
import org.voxis.dictation.engine.TranscriptionResult
import org.voxis.dictation.engine.WhisperInferenceEngine
import org.voxis.dictation.vad.VoiceActivityDetector
import java.util.concurrent.CopyOnWriteArrayList

class DictationClient private constructor(
    private val context: Context,
    private val options: DictationOptions
) {
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val audioStreamer = AudioRecordStreamer()
    private val audioRouteManager = AudioRouteManager(context)
    private val vad = VoiceActivityDetector(silenceThresholdMs = options.silenceTimeoutMs)
    private val textFormatter = TextStreamFormatter(
        autoPunctuate = options.autoPunctuate,
        autoCapitalize = options.autoCapitalize
    )
    private val inferenceEngine = WhisperInferenceEngine(
        model = options.model,
        formatter = textFormatter
    )

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _transcriptions = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 64)
    val transcriptions: SharedFlow<TranscriptionResult> = _transcriptions.asSharedFlow()

    private val _audioLevels = MutableStateFlow(0f)
    val audioLevels: StateFlow<Float> = _audioLevels.asStateFlow()

    private val listeners = CopyOnWriteArrayList<DictationListener>()
    private val fullTranscript = StringBuilder()

    @Synchronized
    fun startListening(): Result<Unit> {
        if (_state.value is DictationState.Listening || _state.value is DictationState.Transcribing) {
            return Result.success(Unit)
        }

        fullTranscript.clear()
        vad.reset()

        updateState(DictationState.Preparing(options.model))

        if (options.requestAudioFocus) {
            audioRouteManager.requestAudioFocus()
        }
        if (options.preferBluetoothSco) {
            audioRouteManager.startBluetoothScoIfAvailable(true)
        }

        sessionJob?.cancel()
        sessionJob = clientScope.launch {
            val initResult = inferenceEngine.initialize()
            if (initResult.isFailure) {
                handleError("Failed to initialize inference engine: ${initResult.exceptionOrNull()?.message}", initResult.exceptionOrNull())
                return@launch
            }

            // Try starting system SpeechRecognizer first if available
            startSystemSpeechRecognizerIfAvailable()

            val audioStart = audioStreamer.start(this)
            if (audioStart.isFailure && speechRecognizer == null) {
                handleError("Failed to start audio recording: ${audioStart.exceptionOrNull()?.message}", audioStart.exceptionOrNull())
                return@launch
            }

            updateState(DictationState.Listening(0f))

            audioStreamer.chunkFlow.collectLatest { chunk ->
                // Map decibels (-60 to 0) to 0.0 - 1.0
                val normalized = ((chunk.rmsDecibels + 60f) / 60f).coerceIn(0f, 1f)
                _audioLevels.value = normalized
                listeners.forEach { it.onAudioLevel(normalized) }

                val vadEvent = vad.processChunk(chunk)
                when (vadEvent) {
                    VoiceActivityDetector.VadEvent.SPEECH_START -> {
                        updateState(DictationState.Transcribing(fullTranscript.toString(), normalized))
                    }
                    VoiceActivityDetector.VadEvent.SPEECH_CONTINUING -> {
                        val partial = inferenceEngine.transcribeChunk(chunk)
                        if (partial != null && partial.text.isNotBlank()) {
                            val combined = if (fullTranscript.isEmpty()) partial.text else "$fullTranscript ${partial.text}"
                            updateState(DictationState.Transcribing(combined, normalized))
                            _transcriptions.emit(partial)
                            listeners.forEach { it.onPartialTranscription(combined) }
                        }
                    }
                    VoiceActivityDetector.VadEvent.SPEECH_END -> {
                        if (!options.continuousMode) {
                            stopListeningInternal()
                        }
                    }
                    VoiceActivityDetector.VadEvent.SILENCE -> {
                        if (_state.value is DictationState.Listening) {
                            updateState(DictationState.Listening(normalized))
                        }
                    }
                }
            }
        }

        return Result.success(Unit)
    }

    private fun startSystemSpeechRecognizerIfAvailable() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer?.destroy()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        updateState(DictationState.Transcribing(fullTranscript.toString(), _audioLevels.value))
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        if (norm > 0f) _audioLevels.value = norm
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        // System recognizer error: fallback seamlessly to local acoustic engine
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            val formatted = textFormatter.format(text)
                            inferenceEngine.setRecognizedText(formatted)
                            if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
                            fullTranscript.append(formatted)
                            val finalResult = TranscriptionResult(text = fullTranscript.toString(), isFinal = true)
                            _transcriptions.tryEmit(finalResult)
                            updateState(DictationState.Transcribing(fullTranscript.toString(), _audioLevels.value))
                            listeners.forEach { it.onFinalTranscription(fullTranscript.toString()) }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            val formatted = textFormatter.format(text)
                            val combined = if (fullTranscript.isEmpty()) formatted else "$fullTranscript $formatted"
                            inferenceEngine.setRecognizedText(combined)
                            val partial = TranscriptionResult(text = combined, isFinal = false)
                            _transcriptions.tryEmit(partial)
                            updateState(DictationState.Transcribing(combined, _audioLevels.value))
                            listeners.forEach { it.onPartialTranscription(combined) }
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer.startListening(intent)
                speechRecognizer = recognizer
            }
        } catch (_: Exception) {
            // Safe fallback to local engine
            speechRecognizer = null
        }
    }

    @Synchronized
    fun stopListening() {
        clientScope.launch {
            stopListeningInternal()
        }
    }

    private suspend fun stopListeningInternal() {
        audioStreamer.stop()
        audioRouteManager.stopBluetoothSco()
        audioRouteManager.abandonAudioFocus()

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null

        val finalResult = inferenceEngine.finalizeTranscription()
        val text = finalResult?.text ?: fullTranscript.toString()

        updateState(DictationState.Complete(text))
        if (finalResult != null) {
            _transcriptions.emit(finalResult)
        }
        listeners.forEach { it.onFinalTranscription(text) }

        _audioLevels.value = 0f
        sessionJob?.cancel()
        sessionJob = null
    }

    @Synchronized
    fun cancel() {
        audioStreamer.stop()
        audioRouteManager.stopBluetoothSco()
        audioRouteManager.abandonAudioFocus()

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null

        inferenceEngine.reset()
        _audioLevels.value = 0f
        sessionJob?.cancel()
        sessionJob = null
        updateState(DictationState.Idle)
    }

    fun injectTranscribedText(text: String) {
        val formatted = textFormatter.format(text)
        inferenceEngine.appendRecognizedToken(formatted)
        if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
        fullTranscript.append(formatted)

        val result = TranscriptionResult(text = fullTranscript.toString(), isFinal = false)
        _transcriptions.tryEmit(result)
        updateState(DictationState.Transcribing(fullTranscript.toString(), 0.8f))
        listeners.forEach { it.onPartialTranscription(fullTranscript.toString()) }
    }

    fun release() {
        cancel()
        inferenceEngine.release()
        listeners.clear()
        clientScope.cancel()
    }

    fun addListener(listener: DictationListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: DictationListener) {
        listeners.remove(listener)
    }

    private fun updateState(newState: DictationState) {
        _state.value = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    private fun handleError(message: String, cause: Throwable?) {
        cancel()
        val errState = DictationState.Error(message, cause)
        _state.value = errState
        listeners.forEach {
            it.onStateChanged(errState)
            it.onError(message, cause)
        }
    }

    class Builder(private val context: Context) {
        private var model: SpeechModel = SpeechModel.TinyEnglish
        private var continuousMode: Boolean = true
        private var autoPunctuate: Boolean = true
        private var autoCapitalize: Boolean = true
        private var preferBluetoothSco: Boolean = true
        private var requestAudioFocus: Boolean = true
        private var silenceTimeoutMs: Long = 1400L

        fun setModel(model: SpeechModel) = apply { this.model = model }
        fun setContinuousMode(enabled: Boolean) = apply { this.continuousMode = enabled }
        fun setAutoPunctuate(enabled: Boolean) = apply { this.autoPunctuate = enabled }
        fun setAutoCapitalize(enabled: Boolean) = apply { this.autoCapitalize = enabled }
        fun setPreferBluetoothSco(enabled: Boolean) = apply { this.preferBluetoothSco = enabled }
        fun setRequestAudioFocus(enabled: Boolean) = apply { this.requestAudioFocus = enabled }
        fun setSilenceTimeoutMs(ms: Long) = apply { this.silenceTimeoutMs = ms }

        fun build(): DictationClient {
            val options = DictationOptions(
                model = model,
                continuousMode = continuousMode,
                autoPunctuate = autoPunctuate,
                autoCapitalize = autoCapitalize,
                preferBluetoothSco = preferBluetoothSco,
                requestAudioFocus = requestAudioFocus,
                silenceTimeoutMs = silenceTimeoutMs
            )
            return DictationClient(context.applicationContext, options)
        }
    }

    companion object {
        fun builder(context: Context): Builder = Builder(context)
    }
}
