package dev.xiaoman.app.assist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.text.input.TextFieldState
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
import java.util.function.Consumer
import kotlin.math.roundToInt

internal class XiaomanVoiceSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner {
    private val graph = (context.applicationContext as XiaomanApp).graph
    private val session = graph.session
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val stateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = stateController.savedStateRegistry

    private val visible = mutableStateOf(false)
    private val draft = TextFieldState()
    private var removeBlurListener: (() -> Unit)? = null
    private var content: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        stateController.performAttach()
        stateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        removeBlurListener?.invoke()
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setBackdropVisible(false)
            setDimAmount(0.28f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val radius = (18 * context.resources.displayMetrics.density).roundToInt()
                attributes = attributes.apply { blurBehindRadius = radius }
                val manager = context.getSystemService(WindowManager::class.java)
                val listener = Consumer<Boolean> { enabled ->
                    setDimAmount(if (enabled) 0.16f else 0.28f)
                }
                manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
                removeBlurListener = { manager.removeCrossWindowBlurEnabledListener(listener) }
            }
            WindowCompat.setDecorFitsSystemWindows(this, false)
            attributes = attributes.apply { setFitInsetsTypes(0) }
            // Mark the adjust mode as explicit so the dialog theme cannot restore panning.
            setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@XiaomanVoiceSession)
            setViewTreeSavedStateRegistryOwner(this@XiaomanVoiceSession)
            setContent {
                if (visible.value) {
                    XiaomanTheme(graph.appearance) {
                        AssistantPanel(
                            session = session,
                            draft = draft,
                            onClose = ::closePanel,
                            onOpenChat = ::openChat,
                            onApprove = { id, scope ->
                                closePanel()
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
        if (session.busy.value && session.question.value == null && session.approval.value == null) {
            closePanel()
            return
        }
        setBackdropVisible(!session.busy.value)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        graph.assistantPanelVisible.value = true
        // A reused assistant window can have its input mode reset when Android shows it.
        // Apply the mode to the attached decor before Compose starts the IME session.
        window.window?.let { window ->
            window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            window.decorView.post {
                if (visible.value) window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
        }
        visible.value = true
    }

    override fun onBackPressed() {
        closePanel()
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
        removeBlurListener?.invoke()
        removeBlurListener = null
        content?.disposeComposition()
        content = null
        super.onDestroy()
    }

    private fun setBackdropVisible(enabled: Boolean) {
        val flags = LayoutParams.FLAG_DIM_BEHIND or LayoutParams.FLAG_BLUR_BEHIND
        window.window?.let { if (enabled) it.addFlags(flags) else it.clearFlags(flags) }
    }

    private fun closePanel() {
        setBackdropVisible(false)
        hide()
    }

    private fun openChat() {
        startAssistantActivity(Intent(context, MainActivity::class.java).setAction(Notifier.ACTION_OPEN_CHAT))
        closePanel()
    }
}
