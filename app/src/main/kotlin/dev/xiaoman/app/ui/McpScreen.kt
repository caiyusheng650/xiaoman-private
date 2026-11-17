package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.mcp.CatalogServer
import dev.xiaoman.mcp.ConfirmPolicy
import dev.xiaoman.mcp.CustomMcpServer
import dev.xiaoman.mcp.McpCatalog
import dev.xiaoman.mcp.McpPlatform
import dev.xiaoman.mcp.McpSelection
import dev.xiaoman.mcp.ServerStatus
import kotlinx.coroutines.launch

/** Services that work on Android, grouped by category. */
private val catalog: List<CatalogServer> by lazy { McpCatalog.builtIn.availableOn(McpPlatform.ANDROID) }

/** Ids ticked by 推荐组合: free or easy to obtain, read-only. */
private val RECOMMENDED = setOf("amap", "aihot")

/**
 * One-tap MCP services: tick a service, paste its key, save.
 *
 * The list scrolls independently from the actions: 保存/测试连接 live in a pinned bar at the
 * bottom so they are never lost between a dozen service cards.
 */
@Composable
fun McpScreen(graph: AppGraph) {
    val store = graph.mcpSettings
    var selection by remember { mutableStateOf(store.current) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val status = remember { mutableStateMapOf<String, ServerStatus>() }
    val scope = rememberCoroutineScope()

    fun update(next: McpSelection) {
        selection = next
        saved = false
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // No subtitle: the confirmation policy it explained is now spelled out by the per-service
        // switch below, and a fixed header keeps the list from moving when the page opens.
        ScreenHeader(title = stringResource(R.string.nav_services))
        LazyColumn(
            Modifier.weight(1f).pageWidth(),
            contentPadding = pageContentPadding,
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        stringResource(R.string.mcp_enabled_count, selection.enabled.size + selection.custom.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedButton(onClick = { update(selection.copy(enabled = selection.enabled + RECOMMENDED)) }) {
                        Text(stringResource(R.string.mcp_recommended))
                    }
                }
            }

            catalog.groupBy { it.category }.forEach { (category, servers) ->
                item(key = "header-$category") { SectionHeader(category) }
                servers.forEach { server ->
                    item(key = server.id) {
                        ServiceCard(server, selection, status[server.id], ::update)
                    }
                }
            }

            item(key = "custom-header") {
                Column(Modifier.padding(top = Space.sm)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SectionHeader(stringResource(R.string.mcp_custom_title))
                    Text(stringResource(R.string.mcp_custom_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            selection.custom.forEachIndexed { i, c ->
                item(key = "custom-${c.url}-$i") {
                    CustomServerCard(
                        custom = c,
                        status = status["custom-${i + 1}"],
                        onRemove = { update(selection.copy(custom = selection.custom.filterIndexed { j, _ -> j != i })) },
                    )
                }
            }
            item(key = "custom-form") { CustomServerForm { update(selection.copy(custom = selection.custom + it)) } }
        }

        BottomActionBar {
            Button(
                onClick = { store.save(selection); saved = true },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_save)) }
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    scope.launch {
                        try {
                            status.clear()
                            graph.testMcp(selection).forEach { status[it.name] = it }
                        } finally {
                            testing = false
                        }
                    }
                },
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(if (testing) R.string.mcp_testing else R.string.mcp_test),
                    Modifier.padding(start = if (testing) Space.sm else 0.dp),
                )
            }
            if (saved) {
                Text(
                    stringResource(R.string.settings_saved),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) = SectionLabel(title)

@Composable
private fun ServiceCard(server: CatalogServer, selection: McpSelection, status: ServerStatus?, onChange: (McpSelection) -> Unit) {
    val enabled = server.id in selection.enabled
    val uri = LocalUriHandler.current
    val missing = server.keys.filter { selection.keys[it.name].isNullOrBlank() }.map { it.label }

    StandardCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(server.title, style = MaterialTheme.typography.titleSmall)
                        Tag(
                            text = stringResource(if (server.official) R.string.mcp_official else R.string.mcp_community),
                            highlight = server.official,
                        )
                    }
                    Text(server.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    onChange(selection.copy(enabled = if (on) selection.enabled + server.id else selection.enabled - server.id))
                })
            }
            if (enabled) {
                server.keys.forEach { key ->
                    OutlinedTextField(
                        value = selection.keys[key.name].orEmpty(),
                        onValueChange = { onChange(selection.copy(keys = selection.keys + (key.name to it.trim()))) },
                        label = { Text(key.label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    key.help?.let { help ->
                        TextButton(onClick = { uri.openUri(help) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(stringResource(R.string.mcp_apply_key), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.mcp_missing_key, missing.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            status?.let { StatusLine(it) }
        }
    }
}

/** Small pill for 官方/社区, tinted with the green containers instead of the Material purple. */
@Composable
private fun Tag(text: String, highlight: Boolean) {
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(status: ServerStatus) {
    if (status.connected) {
        Text(stringResource(R.string.mcp_connected, status.toolCount), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    } else {
        Text(stringResource(R.string.mcp_failed, status.error ?: "?"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CustomServerCard(custom: CustomMcpServer, status: ServerStatus?, onRemove: () -> Unit) {
    StandardCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(custom.title.ifBlank { custom.url }, style = MaterialTheme.typography.titleSmall)
                    Text(custom.url.substringBefore('?'), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.mcp_remove)) }
            }
            status?.let { StatusLine(it) }
        }
    }
}

@Composable
private fun CustomServerForm(onAdd: (CustomMcpServer) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(ConfirmPolicy.WRITES) }
    val problem = url.takeIf { it.isNotBlank() }?.let { McpCatalog.validateCustomUrl(it) }

    StandardCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.mcp_custom_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = url, onValueChange = { url = it.trim() }, label = { Text(stringResource(R.string.mcp_custom_url)) },
                isError = problem != null, supportingText = { problem?.let { Text(stringResource(R.string.mcp_custom_invalid, it)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = auth, onValueChange = { auth = it }, label = { Text(stringResource(R.string.mcp_custom_auth)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.mcp_custom_confirm), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    ConfirmPolicy.WRITES to R.string.mcp_confirm_writes,
                    ConfirmPolicy.ALL to R.string.mcp_confirm_all,
                    ConfirmPolicy.NONE to R.string.mcp_confirm_none,
                ).forEach { (policy, label) ->
                    FilterChip(
                        selected = confirm == policy,
                        onClick = { confirm = policy },
                        label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            OutlinedButton(
                enabled = url.isNotBlank() && problem == null,
                onClick = {
                    val headers = auth.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to it) }.orEmpty()
                    onAdd(CustomMcpServer(title.trim(), url, headers, confirm))
                    title = ""; url = ""; auth = ""; confirm = ConfirmPolicy.WRITES
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.mcp_custom_add), Modifier.padding(start = 6.dp))
            }
        }
    }
}
