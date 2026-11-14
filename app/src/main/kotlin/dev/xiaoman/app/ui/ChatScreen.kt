package dev.xiaoman.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.hands.XiaomanAccessibilityService
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val chatTimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val chatDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun ChatScreen(
    graph: AppGraph,
    onOpenSettings: () -> Unit,
) {
    val session = graph.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val service by XiaomanAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val notificationsGranted = rememberNotificationPermission()
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val rows = remember(items, zone) { chatTimeline(items, zone) }
    val listState = rememberLazyListState()
    val nearBottom = with(LocalDensity.current) { 72.dp.roundToPx() }
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState, nearBottom) {
        var wasScrolling = false
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, index, offset) ->
            if (scrolling || wasScrolling) followLatest = index == 0 && offset <= nearBottom
            wasScrolling = scrolling
        }
    }

    val pendingApproval = approval
    val pendingQuestion = question
    val working = busy && pendingQuestion == null && pendingApproval == null
    val latestItemId = items.lastOrNull()?.id
    LaunchedEffect(latestItemId, working, pendingQuestion?.id, pendingApproval?.id) {
        if (latestItemId != null && followLatest && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }

    LaunchedEffect(pendingApproval?.id, pendingQuestion?.id) {
        if (pendingApproval != null || pendingQuestion != null) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }

    fun jumpToLatest() {
        followLatest = true
        listState.requestScrollToItem(0)
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ChatHeader(
            ready = settings.configured && service != null,
            busy = busy,
            attention = approval != null || question != null,
            onClearHistory = if (items.isNotEmpty() && !busy) ({ confirmClear = true }) else null,
        )
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.chat_clear_history)) },
                text = { Text(stringResource(R.string.chat_clear_history_message)) },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            confirmClear = false
                            if (session.clearHistory()) {
                                jumpToLatest()
                            } else {
                                Toast.makeText(context, R.string.chat_clear_history_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text(stringResource(R.string.chat_clear_history_confirm)) }
                },
            )
        }

        if (service == null) {
            Banner(stringResource(R.string.banner_accessibility), stringResource(R.string.banner_accessibility_action)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!settings.configured) Banner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
        if (!notificationsGranted.granted) Banner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)

        /*
         * The transcript is rendered bottom-anchored (reverseLayout), the way every messaging app
         * behaves: new items appear right above the composer and the view stays pinned there, while
         * someone reading older messages is not yanked down by a late reply. The welcome screen is
         * therefore not a list item — it would otherwise sit at the bottom of an empty list.
         */
        if (rows.isEmpty()) {
            Column(
                Modifier.weight(1f).pageWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(top = Space.sm, bottom = Space.sm),
            ) {
                ChatWelcome { draft = it }
            }
        } else {
            Box(Modifier.weight(1f).pageWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = pageContentPadding,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    if (working) {
                        item(key = "working") {
                            TaskStatus(status ?: stringResource(R.string.chat_working))
                        }
                    }
                    items(rows.asReversed(), key = { it.id }) { row ->
                        when (row) {
                            is TimelineRow.Message -> Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                row.timestamp?.atZone(zone)?.let { time ->
                                    val formatter = if (time.toLocalDate() == LocalDate.now(zone)) {
                                        chatTimeFormat
                                    } else {
                                        chatDateTimeFormat
                                    }
                                    Text(
                                        time.format(formatter),
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                ChatMessage(row.item)
                            }
                            is TimelineRow.Process -> ProcessCard(row.entries)
                        }
                    }
                }
                JumpToLatest(
                    visible = !followLatest,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = Dimens.pageHorizontal, bottom = Space.sm),
                    onClick = ::jumpToLatest,
                )
            }
        }

        if (pendingApproval != null) {
            ApprovalCard(pendingApproval, session)
        } else {
            if (pendingQuestion != null) {
                QuestionCard(pendingQuestion) { option ->
                    if (session.answerQuestion(option, pendingQuestion.id)) jumpToLatest()
                }
            }
            ChatInput(draft, { draft = it }, session, busy, pendingQuestion != null, onSent = ::jumpToLatest)
        }
    }
}

@Composable
internal fun ChatInput(
    text: String,
    onChange: (String) -> Unit,
    session: AgentSession,
    busy: Boolean,
    answering: Boolean,
    onSent: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    fieldColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    requestFocusOnShow: Boolean = false,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestFocusOnShow) {
        if (requestFocusOnShow) {
            requester.requestFocus()
            keyboard?.show()
        }
    }
    val canSend = text.isNotBlank() && (!busy || answering)
    fun send() {
        if (!canSend) return
        when (session.submit(text)) {
            AgentSession.Submit.Busy, AgentSession.Submit.Empty -> Unit
            else -> { onChange(""); focus.clearFocus(); keyboard?.hide(); onSent() }
        }
    }
    Surface(color = containerColor, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.pageWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = text, onValueChange = onChange, modifier = Modifier.weight(1f).focusRequester(requester),
                    placeholder = { Text(stringResource(if (answering) R.string.chat_answer_hint else R.string.chat_input_hint), style = MaterialTheme.typography.bodyMedium) },
                    shape = MaterialTheme.shapes.extraLarge, textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 1, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = fieldColor,
                        unfocusedContainerColor = fieldColor,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (busy && !answering) session.stop() else send() },
                    enabled = (busy && !answering) || canSend,
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                ) {
                    Icon(if (busy && !answering) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = stringResource(if (busy && !answering) R.string.chat_stop else R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = Dimens.pageHorizontal, end = Space.sm, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private class NotificationPermission(val granted: Boolean, val request: () -> Unit)

/** POST_NOTIFICATIONS on Android 13+: asked once automatically, then via the banner. */
@Composable
private fun rememberNotificationPermission(): NotificationPermission {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermission(true) {}
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return NotificationPermission(granted) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
