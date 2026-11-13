package dev.xiaoman.app.assist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.app.MainActivity
import dev.xiaoman.app.Notifier
import dev.xiaoman.app.XiaomanApp
import dev.xiaoman.app.ui.XiaomanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class XiaomanVoiceSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner {
    private val graph = (context.applicationContext as XiaomanApp).graph
    private val session = graph.session
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val stateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = stateController.savedStateRegistry

    private val visible = mutableStateOf(false)
    private val draft = mutableStateOf("")
    private var content: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        stateController.performAttach()
        stateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@XiaomanVoiceSession)
            setViewTreeSavedStateRegistryOwner(this@XiaomanVoiceSession)
            setContent {
                if (visible.value) {
                    XiaomanTheme(graph.appearance) {
                        AssistantPanel(
                            session = session,
                            draft = draft.value,
                            onDraftChange = { draft.value = it },
                            onClose = ::hide,
                            onOpenChat = ::openChat,
                            onApprove = { id, scope ->
                                hide()
                                graph.scope.launch(Dispatchers.Main) {
                                    delay(650)
                                    session.answerApproval(ApprovalAnswer(true, scope), id)
                                }
                            },
                        )
                    }
                }
            }
        }.also { content = it }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        graph.assistantPanelVisible.value = true
        visible.value = true
    }

    override fun onBackPressed() {
        hide()
    }

    override fun onHide() {
        visible.value = false
        graph.assistantPanelVisible.value = false
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        visible.value = false
        graph.assistantPanelVisible.value = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        content?.disposeComposition()
        content = null
        super.onDestroy()
    }

    private fun openChat() {
        startAssistantActivity(Intent(context, MainActivity::class.java).setAction(Notifier.ACTION_OPEN_CHAT))
        hide()
    }
}
