package org.voxis.dictation.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.voxis.dictation.client.DictationClient
import org.voxis.dictation.client.DictationListener
import org.voxis.dictation.client.DictationState
import org.voxis.dictation.engine.SpeechModel
import java.util.ArrayList

class VoxisRecognitionService : RecognitionService() {

    private var activeClient: DictationClient? = null
    private var activeCallback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        activeCallback = callback
        activeClient?.cancel()

        val language = recognizerIntent?.getStringExtra("android.speech.extra.LANGUAGE") ?: "en"
        val model = if (language.startsWith("en", ignoreCase = true)) {
            SpeechModel.TinyEnglish
        } else {
            SpeechModel.SmallMultilingual
        }

        val client = DictationClient.builder(this)
            .setModel(model)
            .setContinuousMode(false)
            .setAutoPunctuate(true)
            .setAutoCapitalize(true)
            .build()

        client.addListener(object : DictationListener {
            override fun onStateChanged(state: DictationState) {
                when (state) {
                    is DictationState.Listening -> {
                        activeCallback?.readyForSpeech(Bundle())
                    }
                    is DictationState.Transcribing -> {
                        activeCallback?.beginningOfSpeech()
                    }
                    is DictationState.Complete -> {
                        val bundle = Bundle().apply {
                            putStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION,
                                ArrayList(listOf(state.finalText))
                            )
                        }
                        activeCallback?.results(bundle)
                        activeCallback = null
                    }
                    is DictationState.Error -> {
                        activeCallback?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                        activeCallback = null
                    }
                    else -> {}
                }
            }

            override fun onPartialTranscription(text: String) {
                val bundle = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        ArrayList(listOf(text))
                    )
                }
                activeCallback?.partialResults(bundle)
            }

            override fun onFinalTranscription(text: String) {
                // Handled in onStateChanged Complete
            }

            override fun onAudioLevel(normalizedLevel: Float) {
                // Convert 0.0..1.0 to rmsdB (-2 to 10)
                val rmsDb = (normalizedLevel * 12f) - 2f
                activeCallback?.rmsChanged(rmsDb)
            }

            override fun onError(error: String, cause: Throwable?) {
                activeCallback?.error(SpeechRecognizer.ERROR_AUDIO)
                activeCallback = null
            }
        })

        activeClient = client
        val result = client.startListening()
        if (result.isFailure) {
            callback?.error(SpeechRecognizer.ERROR_AUDIO)
            activeCallback = null
        }
    }

    override fun onStopListening(callback: Callback?) {
        activeClient?.stopListening()
    }

    override fun onCancel(callback: Callback?) {
        activeClient?.cancel()
        activeClient?.release()
        activeClient = null
        activeCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        activeClient?.release()
        activeClient = null
        activeCallback = null
    }
}
