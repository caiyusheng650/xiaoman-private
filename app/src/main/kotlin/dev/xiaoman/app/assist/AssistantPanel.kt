package dev.xiaoman.app.assist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.ui.ApprovalCard
import dev.xiaoman.app.ui.ChatInput
import dev.xiaoman.app.ui.ChatMessage
import dev.xiaoman.app.ui.Dimens
import dev.xiaoman.app.ui.ProcessCard
import dev.xiaoman.app.ui.QuestionCard
import dev.xiaoman.app.ui.Space
import dev.xiaoman.app.ui.TaskStatus
import dev.xiaoman.app.ui.TimelineRow
import dev.xiaoman.app.ui.chatRichText
import dev.xiaoman.app.ui.chatTimeline

@Composable
internal fun AssistantPanel(
    session: AgentSession,
    speech: AssistantSpeech.State,
    recognizedText: String,
    onMic: () -> Unit,
    onCancelSpeech: () -> Unit,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    onApprove: (Long, ApprovalScope) -> Unit,
) {
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val pendingApproval = approval
    val pendingQuestion = question
    val waiting = busy && pendingQuestion == null && pendingApproval == null
    val speaking = speech.listening || speech.processing
    val listState = rememberLazyListState()
    var historyExpanded by remember { mutableStateOf(false) }
    var replyExpanded by remember(items.lastOrNull()?.id) { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestReply = items.lastOrNull { it is ChatItem.Assistant || it is ChatItem.Error }
    val rows = remember(items, historyExpanded) { if (historyExpanded) chatTimeline(items) else emptyList() }

    LaunchedEffect(pendingQuestion?.id, pendingApproval?.id) {
        if (pendingQuestion != null || pendingApproval != null) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(historyExpanded, items.lastOrNull()?.id) {
        if (historyExpanded && listState.firstVisibleItemIndex <= 1 && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }
    fun openKeyboard(text: String? = null) {
        onCancelSpeech()
        if (text != null) draft = text
        typing = true
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val transcriptHeight = if (typing) {
                (maxHeight * 0.16f).coerceIn(48.dp, 120.dp)
            } else {
                (maxHeight * 0.66f - 116.dp).coerceAtLeast(48.dp)
            }
            Surface(
                modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.sm)
                    .widthIn(max = Dimens.maxContentWidth).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 10.dp,
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(16.dp).pointerInput(historyExpanded) {
                            var drag = 0f
                            detectVerticalDragGestures(
                                onDragStart = { drag = 0f },
                                onDragEnd = {
                                    if (drag < -24.dp.toPx()) historyExpanded = true
                                    if (drag > 24.dp.toPx()) historyExpanded = false
                                },
                            ) { change, amount ->
                                change.consume()
                                drag += amount
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(30.dp, 3.dp),
                            shape = RoundedCornerShape(2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        ) {}
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (historyExpanded) {
                            TextButton(onClick = onOpenChat) { Text(stringResource(R.string.assist_open_app)) }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, stringResource(R.string.assist_close))
                        }
                    }

                    if (historyExpanded) {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = transcriptHeight),
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm),
                            verticalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            items(rows.asReversed(), key = { it.id }) { row ->
                                when (row) {
                                    is TimelineRow.Message -> ChatMessage(row.item)
                                    is TimelineRow.Process -> ProcessCard(row.entries)
                                }
                            }
                        }
                    }

                    val message = speech.message
                    if (speaking || message != null || waiting ||
                        (!historyExpanded && !typing && pendingApproval == null && pendingQuestion == null)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            when {
                                speaking -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                    ) {
                                        if (speech.listening) SpeechWaveform(speech.level)
                                        Text(
                                            stringResource(if (speech.listening) R.string.assist_listening else R.string.assist_recognizing),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        )
                                    }
                                    if (speech.partial.isNotBlank()) {
                                        Text(speech.partial, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                message != null -> {
                                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                                    if (speech.partial.isNotBlank()) {
                                        TextButton(onClick = { openKeyboard(speech.partial) }) { Text(speech.partial) }
                                    }
                                }
                                waiting -> TaskStatus(status ?: stringResource(R.string.chat_working))
                                latestReply != null -> {
                                    val reply = when (latestReply) {
                                        is ChatItem.Assistant -> latestReply.text
                                        is ChatItem.Error -> latestReply.text
                                        else -> error("Expected a reply")
                                    }
                                    var truncated by remember(reply) { mutableStateOf(false) }
                                    Column(Modifier.heightIn(max = transcriptHeight).verticalScroll(rememberScrollState())) {
                                        Text(
                                            chatRichText(reply),
                                            maxLines = if (replyExpanded) Int.MAX_VALUE else 4,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                            onTextLayout = { if (!replyExpanded) truncated = it.hasVisualOverflow },
                                        )
                                    }
                                    if (truncated || replyExpanded) {
                                        TextButton(onClick = { replyExpanded = !replyExpanded }) {
                                            Text(stringResource(if (replyExpanded) R.string.assist_reply_collapse else R.string.assist_reply_expand))
                                        }
                                    }
                                }
                                else -> Text(stringResource(R.string.assist_prompt), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }

                    if (pendingApproval != null) {
                        ApprovalCard(pendingApproval, session) { scope -> onApprove(pendingApproval.id, scope) }
                    } else if (pendingQuestion != null) {
                        QuestionCard(pendingQuestion) { option ->
                            onCancelSpeech()
                            session.answerQuestion(option, pendingQuestion.id)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            enabled = pendingApproval == null,
                            onClick = {
                                if (typing) {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    typing = false
                                } else openKeyboard()
                            },
                        ) { Icon(Icons.Outlined.Keyboard, stringResource(R.string.assist_keyboard)) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            FilledIconButton(
                                enabled = !speech.processing && pendingApproval == null,
                                onClick = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    typing = false
                                    if (waiting) session.stop() else onMic()
                                },
                            ) {
                                val stop = waiting || speech.listening
                                Icon(
                                    if (stop) Icons.Default.Stop else Icons.Outlined.Mic,
                                    stringResource(if (waiting) R.string.chat_stop else if (stop) R.string.assist_finish_voice else R.string.assist_microphone),
                                )
                            }
                        }
                        IconButton(onClick = { historyExpanded = !historyExpanded }) {
                            Icon(
                                if (historyExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                stringResource(if (historyExpanded) R.string.assist_history_collapse else R.string.assist_history_expand),
                            )
                        }
                    }
                    if (recognizedText.isNotBlank() && !typing && !speaking && !historyExpanded && !waiting && pendingApproval == null) {
                        TextButton(
                            onClick = { openKeyboard(recognizedText) },
                            modifier = Modifier.padding(horizontal = Space.lg).align(Alignment.Start),
                        ) {
                            Text(recognizedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (typing && pendingApproval == null) {
                        ChatInput(
                            text = draft,
                            onChange = { draft = it },
                            session = session,
                            busy = busy,
                            answering = pendingQuestion != null,
                            onSent = {
                                typing = false
                                listState.requestScrollToItem(0)
                            },
                            containerColor = MaterialTheme.colorScheme.background,
                            fieldColor = MaterialTheme.colorScheme.surface,
                            requestFocusOnShow = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechWaveform(level: Float) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(36.dp, 22.dp)) {
        val factors = listOf(0.4f, 0.75f, 1f, 0.65f, 0.35f)
        factors.forEachIndexed { index, factor ->
            val x = size.width * (index + 1) / 6
            val halfHeight = size.height * (0.11f + 0.34f * level * factor)
            drawLine(
                color,
                Offset(x, center.y - halfHeight),
                Offset(x, center.y + halfHeight),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
