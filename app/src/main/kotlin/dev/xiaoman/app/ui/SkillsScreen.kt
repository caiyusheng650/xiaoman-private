package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkillsState(val skills: List<Skill> = emptyList(), val problems: List<SkillProblem> = emptyList(), val loading: Boolean = true)

class SkillsViewModel(
    private val repository: SkillRepository,
    private val ready: suspend () -> Unit,
    private val onReloaded: (Int, List<SkillProblem>) -> Unit,
    skillEvents: SharedFlow<AgentEvent>? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SkillsState())
    val state: StateFlow<SkillsState> = _state.asStateFlow()

    init {
        reload()
        // Skills created or deleted from chat land here; rescan so the 能力 page stays in sync.
        skillEvents?.let { events ->
            viewModelScope.launch {
                events.collect { if (it is AgentEvent.SkillUpdated) reload() }
            }
        }
    }

    fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            ready()
            val result = repository.reload()
            onReloaded(result.skills.size, result.problems)
            _state.value = SkillsState(result.skills, result.problems, loading = false)
        }
    }
}

@Composable
fun SkillsScreen(graph: AppGraph) {
    val vm = viewModel {
        SkillsViewModel(graph.skills, { graph.startup.await() }, graph::logSkillProblems, graph.session.events)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader(
            title = stringResource(R.string.skills_title),
            subtitle = if (state.loading) stringResource(R.string.skills_loading) else stringResource(R.string.skills_loaded, state.skills.size),
            action = {
                TextButton(onClick = vm::reload, enabled = !state.loading) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(stringResource(R.string.skills_reload), modifier = Modifier.padding(start = Space.sm))
                }
            },
        )

        LazyColumn(
            Modifier.weight(1f).pageWidth(),
            contentPadding = pageContentPadding,
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            if (!state.loading && state.skills.isEmpty()) {
                item {
                    EmptyHint(
                        icon = Icons.Outlined.Extension,
                        text = stringResource(R.string.skills_empty),
                    )
                }
            }
            items(state.skills, key = { it.name }) { skill ->
                SkillCard(
                    skill = skill,
                    title = skillTitle(skill),
                    apps = appLabels(skill.apps).filter { it.isNotBlank() },
                    sourceLabel = stringResource(if (skill.source.startsWith(graph.bundledSkillsDir.path)) R.string.skills_builtin else R.string.skills_local),
                )
            }
            if (state.problems.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = Space.sm)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            stringResource(R.string.skills_problems),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Space.md, bottom = Space.xs),
                        )
                    }
                }
                items(state.problems) { p -> ProblemCard(p) }
            }
        }
    }
}

@Composable
private fun SkillCard(skill: Skill, title: String, apps: List<String>, sourceLabel: String) {
    StandardCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                        Text(
                            sourceLabel,
                            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (apps.isNotEmpty()) {
                    Text(
                        stringResource(R.string.skills_apps, apps.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The folder name (`taobao-compare`) and package names (`com.taobao.taobao`) are identifiers
 * for the skill loader, not something a user should have to read. The heading of SKILL.md is
 * the human title, and the package manager knows the app names installed on this phone.
 */
private fun skillTitle(skill: Skill): String =
    Regex("(?m)^#\\s+(.+?)\\s*$").find(skill.instructions)?.groupValues?.get(1)
        ?: skill.name.replace('-', ' ')

@Composable
private fun appLabels(packages: List<String>): List<String> {
    val pm = LocalContext.current.packageManager
    val cache = remember { mutableMapOf<String, String>() }
    return remember(packages) {
        packages.map { pkg ->
            cache.getOrPut(pkg) {
                runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault("")
            }
        }
    }
}

@Composable
private fun ProblemCard(problem: SkillProblem) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardShape),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(Dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                // Only the folder name: the full path is noise for someone who just wants their skill to work.
                problem.source.trimEnd('/').substringAfterLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(problem.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
internal fun EmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
