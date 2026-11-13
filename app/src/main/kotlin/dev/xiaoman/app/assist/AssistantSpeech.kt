package dev.xiaoman.app.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.xiaoman.app.R

/** One recognition request at a time. All calls and callbacks run on the main thread. */
internal class AssistantSpeech(
    private val context: Context,
    private val onUpdate: (State) -> Unit,
    private val onText: (String) -> Unit,
) {
    data class State(
        val listening: Boolean = false,
        val processing: Boolean = false,
        val partial: String = "",
        val level: Float = 0f,
        val message: Int? = null,
    )

    private var recognizer: SpeechRecognizer? = null
    private var state = State()
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(languageTag: String?) {
        if (recognizer != null || !hasPermission) return
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        start(onDevice, languageTag)
    }

    private fun start(onDevice: Boolean, languageTag: String?) {
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            update(State(message = R.string.assist_speech_unavailable))
            return
        }
        try {
            val active = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = active
            update(State(listening = true))
            active.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (recognizer === active && state.listening) {
                        update(state.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                    }
                }

                override fun onEndOfSpeech() {
                    if (recognizer === active) update(state.copy(listening = false, processing = true))
                }

                override fun onPartialResults(results: Bundle?) {
                    if (recognizer === active) update(state.copy(partial = results.bestText()))
                }

                override fun onResults(results: Bundle?) {
                    if (recognizer !== active) return
                    val text = results.bestText()
                    release()
                    update(State(message = if (text.isBlank()) R.string.assist_speech_empty else null))
                    if (text.isNotBlank()) onText(text)
                }

                override fun onError(error: Int) {
                    if (recognizer !== active) return
                    release()
                    if (onDevice && error in listOf(
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                            SpeechRecognizer.ERROR_CLIENT,
                        )
                    ) {
                        start(onDevice = false, languageTag = languageTag)
                        return
                    }
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            R.string.assist_speech_empty
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.assist_speech_permission
                        else -> R.string.assist_speech_error
                    }
                    update(State(partial = state.partial, message = message))
                }
            })
            active.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            })
        } catch (_: RuntimeException) {
            release()
            if (onDevice) start(onDevice = false, languageTag = languageTag)
            else update(State(message = R.string.assist_speech_error))
        }
    }

    fun stop() {
        val active = recognizer ?: return
        if (!state.listening) return
        update(state.copy(listening = false, processing = true))
        active.stopListening()
    }

    fun cancel() {
        release()
        update(State())
    }

    private fun release() {
        val active = recognizer
        recognizer = null
        active?.destroy()
    }

    private fun update(next: State) {
        state = next
        onUpdate(next)
    }

    private fun Bundle?.bestText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}
