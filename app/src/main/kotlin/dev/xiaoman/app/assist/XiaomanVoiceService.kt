package dev.xiaoman.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.content.Intent

class XiaomanVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
        )
    }
}

class XiaomanVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = XiaomanVoiceSession(this)
}

/** Older Android releases require a declared recognizer to activate a voice interaction service. */
class XiaomanRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
