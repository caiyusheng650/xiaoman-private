package dev.xiaoman.app.assist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.session.localizedText
import dev.xiaoman.app.ui.ApprovalCard
import dev.xiaoman.app.ui.Dimens
import dev.xiaoman.app.ui.QuestionCard
import dev.xiaoman.app.ui.Space
import dev.xiaoman.app.ui.chatRichText
import kotlinx.coroutines.flow.first

@Composable
internal fun AssistantPanel(
    session: AgentSession,
    draft: TextFieldState,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    onApprove: (Long, ApprovalScope) -> Unit,
) {
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val pendingApproval = approval
    val pendingQuestion = question
    val running = busy && pendingQuestion == null && pendingApproval == null
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val focus = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }

    // Opening the overlay should not show a reply from an earlier conversation.
    val previousReplyId = remember { session.items.value.latestReply()?.id }
    val reply = items.latestReply()?.takeUnless { it.id == previousReplyId }
    var replyExpanded by remember(reply?.id) { mutableStateOf(false) }

    fun dismissKeyboard() {
        keyboard?.hide()
        focus.clearFocus()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        if (!running && session.approval.value == null) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(pendingApproval?.id) {
        if (pendingApproval != null) dismissKeyboard()
    }

    LaunchedEffect(running) {
        if (running) {
            dismissKeyboard()
            onClose()
        }
    }

    if (running) return

    fun send() {
        // Read directly from the editor, including the IME's current composing text.
        val message = draft.text.toString().trim()
        if (message.isEmpty() || session.approval.value != null) return
        val accepted = if (pendingQuestion != null) {
            session.answerQuestion(message, pendingQuestion.id)
        } else {
            if (session.busy.value) return
            when (session.submit(message)) {
                AgentSession.Submit.Busy, AgentSession.Submit.Empty -> false
                else -> true
            }
        }
        if (accepted) {
            draft.clearText()
            dismissKeyboard()
            if (session.busy.value) onClose()
        }
    }

    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, colors.primaryContainer.copy(alpha = 0.16f)),
                ),
            ).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.assist_close),
                onClick = onClose,
            ),
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = Space.sm)
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = colors.surfaceContainerHigh.copy(alpha = 0.86f),
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 10.dp,
        ) {
            Column(
                Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                when {
                    pendingApproval != null -> ApprovalCard(pendingApproval, session) { scope ->
                        onApprove(pendingApproval.id, scope)
                    }
                    pendingQuestion != null -> QuestionCard(pendingQuestion) { option ->
                        if (session.answerQuestion(option, pendingQuestion.id)) {
                            dismissKeyboard()
                            onClose()
                        }
                    }
                    reply != null -> ReplyCard(
                        item = reply,
                        expanded = replyExpanded,
                        onToggle = {
                            dismissKeyboard()
                            replyExpanded = !replyExpanded
                        },
                        onOpenChat = onOpenChat,
                    )
                }

                if (pendingApproval == null) {
                    MinimalInputBar(
                        draft = draft,
                        canSend = draft.text.isNotBlank() && (!busy || pendingQuestion != null),
                        answering = pendingQuestion != null,
                        fieldFocus = fieldFocus,
                        onSend = ::send,
                    )
                }
            }
        }
    }
}

private fun List<ChatItem>.latestReply(): ChatItem? =
    lastOrNull { it is ChatItem.Assistant || it is ChatItem.Error }

@Composable
private fun ReplyCard(item: ChatItem, expanded: Boolean, onToggle: () -> Unit, onOpenChat: () -> Unit) {
    val text = when (item) {
        is ChatItem.Assistant -> item.text
        is ChatItem.Error -> item.localizedText()
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(if (expanded) R.string.assist_reply_collapse else R.string.assist_reply_expand),
                        onClick = onToggle,
                    )
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                val scroll = if (expanded) Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    else Modifier
                Text(
                    chatRichText(text),
                    modifier = Modifier.weight(1f).then(scroll),
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                TextButton(onClick = onOpenChat, modifier = Modifier.align(Alignment.End).padding(end = Space.sm)) {
                    Text(stringResource(R.string.assist_open_app))
                }
            }
        }
    }
}

@Composable
private fun MinimalInputBar(
    draft: TextFieldState,
    canSend: Boolean,
    answering: Boolean,
    fieldFocus: FocusRequester,
    onSend: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                state = draft,
                modifier = Modifier.weight(1f).height(48.dp).focusRequester(fieldFocus),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                onKeyboardAction = { onSend() },
                decorator = { inner ->
                    Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.text.isEmpty()) {
                            Text(
                                stringResource(if (answering) R.string.chat_answer_hint else R.string.assist_minimal_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.ArrowUpward,
                    stringResource(R.string.chat_send),
                    tint = if (canSend) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
