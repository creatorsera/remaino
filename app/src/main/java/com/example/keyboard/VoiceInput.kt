package com.example.keyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Small wrapper around Android's speech recognizer. It asks for the on-device
 * recognizer when the phone has one and always sets EXTRA_PREFER_OFFLINE, so a
 * downloaded offline language pack is used instead of sending audio away.
 */
class VoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    var onText: ((String) -> Unit)? = null
    var onState: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isListening: Boolean get() = listening

    fun start() {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition isn't available on this device.")
            return
        }
        val rec = if (Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = rec
        rec.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        onState?.invoke(true)
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            listening = false
            onState?.invoke(false)
            onError?.invoke("Could not start speech recognition.")
        }
    }

    fun stop() {
        val rec = recognizer ?: return
        try {
            rec.cancel()
        } catch (e: Exception) {
        }
        rec.destroy()
        recognizer = null
        if (listening) {
            listening = false
            onState?.invoke(false)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            onState?.invoke(false)
        }

        override fun onError(error: Int) {
            listening = false
            onState?.invoke(false)
            onError?.invoke(messageFor(error))
            stop()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            onState?.invoke(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!text.isNullOrEmpty()) onText?.invoke(text)
            else onError?.invoke("Didn't catch that. Tap the mic and try again.")
            stop()
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for speech."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech needs an offline language pack or a network connection."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Tap the mic and try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech is busy. Try the mic again."
        SpeechRecognizer.ERROR_SERVER -> "The speech service could not be reached."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the mic and try again."
        else -> "Speech recognition failed. Try the mic again."
    }
}
