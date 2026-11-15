package dev.xiaoman.app.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.R as HandsR
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of the chat list. */
@Serializable
sealed class ChatItem {
    abstract val id: Long

    @Serializable
    @SerialName("user")
    data class User(override val id: Long, val text: String, val sentAt: Long? = null, val imagePath: String? = null) : ChatItem()

    @Serializable
    @SerialName("assistant")
    data class Assistant(override val id: Long, val text: String, val sentAt: Long? = null) : ChatItem()

    /** Compact progress line: steps, skill chosen, memory updated, task result. */
    @Serializable
    @SerialName("progress")
    data class Progress(override val id: Long, val text: String, val ok: Boolean? = null) : ChatItem()

    @Serializable
    @SerialName("question")
    data class Question(override val id: Long, val text: String, val takeover: Boolean) : ChatItem()

    @Serializable
    @SerialName("error")
    data class Error(override val id: Long, val text: String) : ChatItem() {
        // Older histories persisted the rendered error with no code. Match the exact
        // strings shipped in both languages, leaving arbitrary device errors intact.
        internal val messageResource: Int?
            get() = when (text) {
                AccessibilityDevice.NOT_CONNECTED,
                "Accessibility service is off. Enable it in Android Settings to let Xiaoman control the device." ->
                    HandsR.string.hands_service_disconnected
                else -> null
            }
    }
}

@Composable
internal fun ChatItem.Error.localizedText(): String =
    messageResource?.let { stringResource(it) } ?: text
