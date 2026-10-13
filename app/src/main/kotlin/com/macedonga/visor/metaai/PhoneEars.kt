package com.macedonga.visor.metaai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * The phone's microphone while the mic is held: the phone's recogniser, on the phone when it holds the language,
 * with the words so far as they come and the final text when the mic is let go. Main thread only.
 */
class PhoneEars(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun ready()
        fun heard(text: String)
        fun level(value: Float)
        /** The words when the mic was let go; null when nothing was understood. */
        fun done(text: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var last = ""
    private val giveUp = Runnable { finish(last.ifBlank { null }) }

    fun start(locale: Locale) {
        cancel()
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        listen(locale.toLanguageTag(), onDevice)
    }

    /** The mic was let go: the last words come back through [Listener.done]. */
    fun stop() {
        val r = recognizer ?: return
        stopping = true
        r.stopListening()
        main.postDelayed(giveUp, GIVE_UP_MS)
    }

    fun cancel() {
        main.removeCallbacks(giveUp)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        stopping = false
        last = ""
    }

    private fun finish(text: String?) {
        if (recognizer == null) return
        cancel()
        listener.done(text)
    }

    private fun listen(tag: String, onDevice: Boolean) {
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.ready()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    last = text
                    listener.heard(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: last
                finish(text.ifBlank { null })
            }

            override fun onError(error: Int) {
                // The phone does not hold this language: its usual recogniser instead, which works while Visor is in front.
                if (onDevice && !stopping && error in LANGUAGE_MISSING) {
                    r.destroy()
                    if (recognizer === r) listen(tag, onDevice = false)
                    return
                }
                finish(last.ifBlank { null })
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Held down, a pause is not the end.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            },
        )
    }

    private companion object {
        const val GIVE_UP_MS = 3_000L
        // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE.
        val LANGUAGE_MISSING = setOf(12, 13)
    }
}
