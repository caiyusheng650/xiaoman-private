package dev.xiaoman.app

import android.content.Intent
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.settings.AppLanguage
import dev.xiaoman.app.settings.AppearanceSettings
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.hands.StatusOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows the hands' [StatusOverlay] (step text + Stop) while the session is busy and the
 * accessibility service is connected. The chat screen renders progress inline.
 */
class OverlayController(
    private val session: AgentSession,
    private val device: AccessibilityDevice,
    private val conversationVisible: Flow<Boolean>,
    private val appearance: AppearanceSettings,
    private val services: StateFlow<XiaomanAccessibilityService?> = XiaomanAccessibilityService.instance,
) {
    private var overlay: StatusOverlay? = null
    private var owner: XiaomanAccessibilityService? = null
    private var overlayLanguage: AppLanguage? = null
    private var fallbackLabel = ""

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Main.immediate) {
        combine(services, session.busy, session.status, conversationVisible, appearance.language) {
                service, busy, status, visible, language ->
            Triple(service.takeIf { busy && !visible }, status, language)
        }
            .collect { (service, status, language) ->
                if (service == null) {
                    remove()
                    return@collect
                }
                if (owner !== service || overlayLanguage != language) {
                    remove()
                    val localized = appearance.localizedContext(service, language)
                    fallbackLabel = localized.getString(R.string.chat_working)
                    overlayLanguage = language
                    overlay = StatusOverlay(localized).also {
                        it.onStop = { session.stop() }
                        it.onOpen = { service.startActivity(Intent(service, MainActivity::class.java)
                            .setAction(Notifier.ACTION_OPEN_CHAT)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                        device.overlay = it
                        it.show()
                    }
                    owner = service
                }
                overlay?.update(status ?: fallbackLabel)
            }
    }

    private fun remove() {
        overlay?.hide()
        overlay = null
        owner = null
        overlayLanguage = null
        device.overlay = null
    }
}
