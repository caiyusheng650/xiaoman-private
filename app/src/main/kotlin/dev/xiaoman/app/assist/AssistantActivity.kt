package dev.xiaoman.app.assist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.text.input.TextFieldState
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.app.MainActivity
import dev.xiaoman.app.Notifier
import dev.xiaoman.app.XiaomanApp
import dev.xiaoman.app.ui.XiaomanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plain-activity host for the assistant panel. A third-party VoiceInteractionService can be
 * refused as the system default digital assistant on some ROMs, so this activity also answers
 * ACTION_ASSIST directly and lets the pet open the same panel without relying on the system
 * assistant picker.
 */
class AssistantActivity : ComponentActivity() {
    private val draft = TextFieldState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as XiaomanApp).graph
        graph.assistantPanelVisible.value = true
        setContent {
            XiaomanTheme(graph.appearance) {
                AssistantPanel(
                    session = graph.session,
                    draft = draft,
                    onClose = ::finish,
                    onOpenChat = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .setAction(Notifier.ACTION_OPEN_CHAT)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                        finish()
                    },
                    onApprove = { id, scope ->
                        finish()
                        graph.scope.launch(Dispatchers.Main) {
                            delay(650)
                            graph.session.answerApproval(ApprovalAnswer(true, scope), id)
                        }
                    },
                )
            }
        }
    }

    override fun onStop() {
        (application as XiaomanApp).graph.assistantPanelVisible.value = false
        super.onStop()
    }

    override fun onDestroy() {
        (application as XiaomanApp).graph.assistantPanelVisible.value = false
        super.onDestroy()
    }
}
