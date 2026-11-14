package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.lounge.Lounge
import dev.xiaoman.app.lounge.LoungeLine
import dev.xiaoman.app.lounge.LoungeMember
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Date

@Composable
private fun LoungeSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LoungeScreen(graph: AppGraph, onSettings: () -> Unit) {
    val lounge = graph.lounge
    val room by lounge.history.collectAsStateWithLifecycle()
    if (!room.enabled) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeader(title = stringResource(R.string.lounge_title))
            Column(
                Modifier.weight(1f).pageWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.lounge_settings_description), textAlign = TextAlign.Center)
                Button(onClick = { lounge.setEnabled(true) }) {
                    Text(stringResource(R.string.lounge_enable))
                }
            }
        }
        return
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val busy by lounge.busy.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var today by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var writing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf<LoungeMember?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    val remaining = room.remaining(today)
    val canGuide = !room.paused && settings.chat.configured && remaining > 0
    val context = LocalContext.current
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    val dateFormat = android.text.format.DateFormat.getMediumDateFormat(context)

    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now().toString()
            delay(30_000)
        }
    }
    LaunchedEffect(room.rounds.lastOrNull()?.at, list.firstVisibleItemIndex) {
        if (list.firstVisibleItemIndex == 0) lounge.markRead()
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.pageWidth().fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                Text(
                    stringResource(R.string.lounge_title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { lounge.setPaused(!room.paused) }) {
                    Text(stringResource(if (room.paused) R.string.lounge_resume else R.string.lounge_pause))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.lounge_options))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        LoungeSwitch(context.getString(R.string.lounge_auto), room.automatic, lounge::setAutomatic)
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.lounge_clear)) },
                            enabled = room.rounds.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                clearing = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.lounge_disable)) },
                            onClick = {
                                menuOpen = false
                                lounge.setEnabled(false)
                            },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoungeMember.entries.forEach { LoungeAvatar(it) }
                Text(
                    stringResource(
                        when {
                            room.paused -> R.string.lounge_paused
                            busy -> R.string.lounge_busy
                            else -> R.string.lounge_resting
                        },
                    ),
                    Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (room.rounds.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.lounge_empty),
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(room.rounds.asReversed(), key = { _, round -> round.at }) { index, round ->
                        val older = room.rounds.asReversed().getOrNull(index + 1)
                        val date = Date(round.at)
                        val label = dateFormat.format(date)
                        val newDay = older == null || dateFormat.format(Date(older.at)) != label
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (older == null || newDay || round.at - older.at >= 5 * 60_000L) {
                                Text(
                                    if (newDay) "$label · ${timeFormat.format(date)}" else timeFormat.format(date),
                                    Modifier.align(Alignment.CenterHorizontally),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            round.lines.forEach { line ->
                                if (line.member == null) {
                                    val guidance = round.target?.let {
                                        stringResource(R.string.lounge_guided_target, stringResource(it.label), line.text)
                                    } ?: stringResource(R.string.lounge_guided, line.text)
                                    Text(guidance, Modifier.align(Alignment.CenterHorizontally), textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelMedium)
                                } else {
                                    LoungeBubble(line, line.member)
                                }
                            }
                        }
                    }
                }
            }
            if (!settings.chat.configured) {
                TextButton(onClick = onSettings) { Text(stringResource(R.string.lounge_configure)) }
            }
            Button(
                onClick = { writing = true },
                enabled = canGuide && !busy,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(stringResource(R.string.lounge_guide, remaining)) }
        }
    }

    if (writing) {
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text(context.getString(R.string.lounge_pick_topic)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf(null) + LoungeMember.entries).forEach { member ->
                            FilterChip(
                                selected = target == member,
                                onClick = { target = member },
                                enabled = !busy,
                                label = { Text(context.getString(member?.label ?: R.string.lounge_everyone)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            if (it.length <= Lounge.TOPIC_LIMIT) draft = it
                            failed = false
                        },
                        label = { Text(context.getString(R.string.lounge_topic_hint)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (busy) Text(context.getString(R.string.lounge_busy))
                    if (failed) Text(context.getString(R.string.lounge_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canGuide && !busy && draft.isNotBlank(),
                    onClick = {
                        failed = false
                        lounge.submit(draft, target) { success ->
                            if (success) {
                                draft = ""
                                target = null
                                writing = false
                                list.requestScrollToItem(0)
                            } else {
                                failed = true
                            }
                        }
                    },
                ) { Text(context.getString(R.string.lounge_pick_topic)) }
            },
            dismissButton = {
                TextButton(onClick = { writing = false }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(context.getString(R.string.lounge_clear)) },
            text = { Text(context.getString(R.string.lounge_clear_message)) },
            confirmButton = {
                TextButton(onClick = { lounge.clear(); clearing = false }) { Text(context.getString(R.string.lounge_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LoungeAvatar(member: LoungeMember) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (member) {
        LoungeMember.XIAOMAN -> colors.primaryContainer to colors.onPrimaryContainer
        LoungeMember.ALI -> colors.secondaryContainer to colors.onSecondaryContainer
        LoungeMember.WENWEN -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(Modifier.size(32.dp), shape = CircleShape, color = background, contentColor = foreground) {
        Box(contentAlignment = Alignment.Center) {
            Text(stringResource(member.initial), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LoungeBubble(line: LoungeLine, member: LoungeMember) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LoungeAvatar(member)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(member.label), style = MaterialTheme.typography.labelMedium)
            Surface(shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(line.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
