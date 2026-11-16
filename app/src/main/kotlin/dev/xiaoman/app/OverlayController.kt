package dev.xiaoman.app

import android.content.Intent
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.settings.AppLanguage
import dev.xiaoman.app.settings.AppearanceSettings
import dev.xiaoman.app.settings.PetPosition
import dev.xiaoman.app.settings.PetSettings
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.PetOverlay
import dev.xiaoman.hands.PetSpriteSheet
import dev.xiaoman.hands.PetState
import dev.xiaoman.hands.StatusOverlay
import dev.xiaoman.hands.XiaomanAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages two overlaid UI elements on top of the accessibility window:
 *   - The small progress pill ([StatusOverlay]) while the task is running and the chat screen
 *     is not visible.
 *   - A draggable pixel pet ([PetOverlay]) that mirrors the agent's state regardless of where
 *     the user is. The pet is optional and can be turned off in Settings.
 *
 * The two overlays are owned independently: the pet must never tear down the progress pill (and
 * vice versa), otherwise enabling the pet silently disables the pill for the rest of the session.
 */
class OverlayController(
    private val session: AgentSession,
    private val device: AccessibilityDevice,
    private val conversationVisible: Flow<Boolean>,
    private val appearance: AppearanceSettings,
    private val petSettings: PetSettings,
    private val services: StateFlow<XiaomanAccessibilityService?> = XiaomanAccessibilityService.instance,
) {
    private var overlay: StatusOverlay? = null
    private var pet: PetOverlay? = null
    private var pillOwner: XiaomanAccessibilityService? = null
    private var pillLanguage: AppLanguage? = null
    private var petOwner: XiaomanAccessibilityService? = null
    private var petCustomPath: String? = null
    private var petSheetVersion = -1
    private var fallbackLabel = ""

    fun start(scope: CoroutineScope): Job {
        scope.launch(Dispatchers.Main.immediate) { manageStatusPill() }
        return scope.launch(Dispatchers.Main.immediate) { managePet() }
    }

    private suspend fun manageStatusPill() {
        combine(services, session.busy, session.status, conversationVisible, appearance.language) {
                service, busy, status, visible, language ->
            Triple(service.takeIf { busy && !visible }, status, language)
        }
            .collect { (service, status, language) ->
                if (service == null) {
                    removeStatus()
                    return@collect
                }
                // Recreate after a service reconnect, a language switch, or a previous removal.
                if (overlay == null || pillOwner !== service || pillLanguage != language) {
                    removeStatus()
                    val localized = appearance.localizedContext(service, language)
                    fallbackLabel = localized.getString(R.string.chat_working)
                    pillOwner = service
                    pillLanguage = language
                    overlay = StatusOverlay(localized).also {
                        it.onStop = { session.stop() }
                        it.onOpen = openChat(service)
                        device.overlay = it
                        it.show()
                    }
                }
                overlay?.update(status ?: fallbackLabel)
            }
    }

    private suspend fun managePet() {
        // The upload always lands on the same file, so the path alone cannot tell a re-upload apart;
        // the version counter is what tells the sheet to reload.
        val petSheet = combine(petSettings.customPath, petSettings.sheetVersion) { path, version -> path to version }
        val petFlow = combine(services, petSettings.enabled, petSheet, petSettings.position, session.busy) {
                service, enabled, sheet, pos, busy ->
            PetInput(service?.takeIf { enabled }, sheet.first, sheet.second, pos, busy, null)
        }
        combine(petFlow, session.lastOutcome) { input, outcome -> input.copy(outcome = outcome) }
            .collect { input ->
                val service = input.service
                if (service == null) {
                    removePet()
                    return@collect
                }
                // A null pet means it was switched off (or never created) and has to come back.
                if (pet == null || petOwner !== service) {
                    removePet()
                    val localized = appearance.localizedContext(service, appearance.language.value)
                    val fresh = PetOverlay(localized).also { p ->
                        p.onClick = openChat(service)
                        p.onRestAt = { x, y -> petSettings.setPosition(x, y) }
                    }
                    pet = fresh
                    petOwner = service
                    loadSheet(fresh, localized, input.customPath, input.sheetVersion)
                    fresh.show(input.position.xDp.takeIf { it >= 0 }, input.position.yDp.takeIf { it >= 0 })
                } else {
                    pet?.let { current ->
                        loadSheetIfChanged(current, service, input.customPath, input.sheetVersion)
                        if (!current.isShowing) {
                            current.show(input.position.xDp.takeIf { it >= 0 }, input.position.yDp.takeIf { it >= 0 })
                        }
                    }
                }
                pet?.setState(petState(input.busy, input.outcome))
            }
    }

    private fun petState(busy: Boolean, outcome: Boolean?): PetState = when {
        // Rows follow the ChatGPT desktop pet contract: 7 while a round runs, 8 when it finished,
        // 5 on failure, back to 0 otherwise.
        busy -> PetState.WORKING
        outcome == true -> PetState.REVIEW
        outcome == false -> PetState.FAILED
        else -> PetState.IDLE
    }

    private suspend fun loadSheetIfChanged(pet: PetOverlay, context: android.content.Context, customPath: String?, version: Int) {
        if (customPath == petCustomPath && version == petSheetVersion) return
        loadSheet(pet, context, customPath, version)
    }

    /**
     * Decoding the atlas takes a few dozen milliseconds and must stay off the main thread: a
     * blocked main thread during startup is exactly what makes Android show "app not responding".
     */
    private suspend fun loadSheet(pet: PetOverlay, context: android.content.Context, customPath: String?, version: Int) {
        val sheet = withContext(Dispatchers.Default) {
            runCatching {
                customPath?.let { PetSpriteSheet.fromFile(File(it)) }
                    ?: PetSpriteSheet.default(context)
            }.getOrNull()
        }
        petCustomPath = customPath
        petSheetVersion = version
        pet.setSheet(sheet)
    }

    private fun openChat(service: XiaomanAccessibilityService): () -> Unit = {
        service.startActivity(Intent(service, MainActivity::class.java)
            .setAction(Notifier.ACTION_OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun removeStatus() {
        overlay?.hide()
        overlay = null
        pillOwner = null
        pillLanguage = null
        device.overlay = null
    }

    private fun removePet() {
        pet?.hide()
        pet = null
        petOwner = null
        petCustomPath = null
        petSheetVersion = -1
    }

    private data class PetInput(
        val service: XiaomanAccessibilityService?,
        val customPath: String?,
        val sheetVersion: Int,
        val position: PetPosition,
        val busy: Boolean,
        val outcome: Boolean?,
    )
}
