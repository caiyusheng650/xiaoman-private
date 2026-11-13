package dev.xiaoman.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

/**
 * Four destinations. 技能 and 服务 were two scrolling lists of the same kind of thing —
 * "what Xiaoman can do" — so they now share one 能力 tab with a segmented switch.
 */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    CAPABILITIES(R.string.nav_abilities, Icons.Filled.Extension),
    MEMORY(R.string.nav_memory, Icons.Filled.Psychology),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

private enum class Capability(@StringRes val label: Int) {
    SKILLS(R.string.nav_skills),
    SERVICES(R.string.nav_services),
}

/**
 * One adaptive UI for phones and tablets: the Material navigation suite picks the bottom bar on
 * compact widths and a side rail on wide windows, so no page needs a second layout.
 * While the keyboard is open the suite is hidden entirely to give chat the full height.
 */
@Composable
fun XiaomanRoot(graph: AppGraph, chatRequest: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var handledChatRequest by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(chatRequest) {
        if (chatRequest > handledChatRequest) {
            tab = Tab.CHAT
            handledChatRequest = chatRequest
        }
    }
    val question by graph.session.question.collectAsStateWithLifecycle()
    LaunchedEffect(question?.id) {
        if (question != null) tab = Tab.CHAT
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val layoutType = if (keyboardOpen) {
        NavigationSuiteType.None
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    }
    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            Tab.entries.forEach { t ->
                item(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                )
            }
        },
    ) {
        // The navigation suite only handles its own chrome, so the status bar inset is ours.
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            when (tab) {
                Tab.CHAT -> ChatScreen(graph, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.CAPABILITIES -> CapabilitiesScreen(graph)
                Tab.MEMORY -> MemoryScreen(graph)
                Tab.SETTINGS -> SettingsScreen(graph)
            }
        }
    }
}

@Composable
private fun CapabilitiesScreen(graph: AppGraph) {
    var section by rememberSaveable { mutableStateOf(Capability.SKILLS) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(
            Modifier.pageWidth().padding(horizontal = Dimens.pageHorizontal, vertical = Space.sm),
        ) {
            Capability.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = section == item,
                    onClick = { section = item },
                    shape = SegmentedButtonDefaults.itemShape(index, Capability.entries.size),
                ) { Text(stringResource(item.label)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                Capability.SKILLS -> SkillsScreen(graph)
                Capability.SERVICES -> McpScreen(graph)
            }
        }
    }
}
