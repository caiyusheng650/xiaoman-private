package dev.xiaoman.app.assist

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.xiaoman.app.XiaomanApp

/** Requests the microphone permission from an activity because the assistant is a service window. */
class AssistantMicrophonePermissionActivity : ComponentActivity() {
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        (application as XiaomanApp).graph.assistantMicrophoneGranted.value = granted
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
