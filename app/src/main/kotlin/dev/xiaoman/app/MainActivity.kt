package dev.xiaoman.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import dev.xiaoman.app.ui.XiaomanRoot
import dev.xiaoman.app.ui.XiaomanTheme

class MainActivity : ComponentActivity() {
    private val chatRequest = mutableIntStateOf(0)
    override fun onStart() {
        super.onStart()
        (application as XiaomanApp).graph.uiVisible.value = true
    }

    override fun onStop() {
        (application as XiaomanApp).graph.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as XiaomanApp).graph
        graph.lounge.reconcileSchedule()
        chatRequest.intValue = savedInstanceState?.getInt("chatRequest")
            ?: if (intent.action == Notifier.ACTION_OPEN_CHAT) 1 else 0
        setContent {
            XiaomanTheme(graph.appearance, onDarkModeChanged = { dark ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }) {
                XiaomanRoot(graph, chatRequest.intValue)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("chatRequest", chatRequest.intValue)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Notifier.ACTION_OPEN_CHAT) chatRequest.intValue++
    }
}
