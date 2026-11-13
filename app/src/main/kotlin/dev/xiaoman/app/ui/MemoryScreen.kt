package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.memory.BasicMemoryFormat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row of the memory list: every note when the query is blank, search hits otherwise. */
data class MemoryRow(val permalink: String, val title: String, val detail: String)

data class MemoryState(
    val query: String = "",
    val rows: List<MemoryRow> = emptyList(),
    val loading: Boolean = true,
    /** The opened note, rendered as Markdown text. */
    val open: Note? = null,
    val openMarkdown: String = "",
    val openMissing: Boolean = false,
)

@OptIn(FlowPreview::class)
class MemoryViewModel(private val memory: MemoryStore) : ViewModel() {
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch { query.debounce(250).collect { refresh() } }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        query.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val q = _state.value.query.trim()
            val rows = runCatching {
                if (q.isEmpty()) {
                    memory.list().sortedByDescending { it.updatedAtMillis }.map { note ->
                        // A note's permalink is an internal key (`places/家`); show what it actually says.
                        val detail = note.observations.joinToString("；") { it.content }
                            .ifBlank {
                                note.body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }.orEmpty()
                            }
                        MemoryRow(note.permalink, note.title, detail)
                    }
                } else {
                    memory.search(q, limit = 50).map { MemoryRow(it.permalink, it.title, it.snippet.replace('\n', ' ')) }
                }
            }.getOrDefault(emptyList())
            _state.update { it.copy(rows = rows, loading = false) }
        }
    }

    fun open(permalink: String) {
        viewModelScope.launch {
            val note = runCatching { memory.read(permalink) }.getOrNull()
            _state.update { it.copy(open = note, openMarkdown = note?.let(BasicMemoryFormat::render).orEmpty(), openMissing = note == null) }
        }
    }

    fun close() = _state.update { it.copy(open = null, openMarkdown = "", openMissing = false) }

    fun delete(permalink: String) {
        viewModelScope.launch {
            runCatching { memory.delete(permalink) }
            close()
            refresh()
        }
    }
}

@Composable
fun MemoryScreen(graph: AppGraph) {
    val vm = viewModel { MemoryViewModel(graph.memory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val note = state.open
    if (note != null) NoteView(note, state.openMarkdown, onBack = vm::close, onDelete = { vm.delete(note.permalink) }) else MemoryList(state, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryList(state: MemoryState, vm: MemoryViewModel) {
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader(
            title = stringResource(R.string.nav_memory),
            subtitle = if (!state.loading && state.rows.isNotEmpty()) stringResource(R.string.memory_count, state.rows.size) else null,
        )
        Column(Modifier.pageWidth().padding(horizontal = Dimens.pageHorizontal)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(19.dp)) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.memory_search_hint), style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.pageWidth().padding(top = Space.sm))
        if (state.openMissing) {
            Text(stringResource(R.string.memory_not_found), modifier = Modifier.padding(Dimens.pageHorizontal), color = MaterialTheme.colorScheme.error)
        }

        if (!state.loading && state.rows.isEmpty()) {
            EmptyHint(
                icon = Icons.Outlined.Description,
                text = stringResource(if (state.query.isBlank()) R.string.memory_empty else R.string.memory_no_hits),
            )
        }

        LazyColumn(
            Modifier.weight(1f).pageWidth(),
            contentPadding = pageContentPadding,
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            items(state.rows, key = { it.permalink }) { row ->
                MemoryCard(row) { vm.open(row.permalink) }
            }
        }
    }
}

@Composable
private fun MemoryCard(row: MemoryRow, onClick: () -> Unit) {
    StandardCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    row.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NoteView(note: Note, markdown: String, onBack: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.pageWidth().padding(horizontal = Space.xs, vertical = Space.xs)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(note.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.memory_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SelectionContainer {
            Column(
                Modifier.weight(1f).pageWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(bottom = Space.lg),
            ) {
                Card(
                    shape = RoundedCornerShape(Dimens.cardShape),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        markdown,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Dimens.cardPadding),
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_message, note.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(stringResource(R.string.memory_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
