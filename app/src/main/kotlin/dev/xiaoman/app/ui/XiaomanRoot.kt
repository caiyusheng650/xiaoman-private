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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
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
 * The bottom bar is gradually covered by the keyboard, while a side rail stays in place.
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
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val ime = WindowInsets.ime
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavigationSuiteScaffoldLayout(
            layoutType = layoutType,
            navigationSuite = {
                val modifier = if (layoutType == NavigationSuiteType.NavigationBar) {
                    Modifier.clipToBounds().layout { measurable, constraints ->
                        // Content already reserves the IME inset. Only reserve the part of
                        // the bottom bar that the keyboard does not cover, on the same frame.
                        val bar = measurable.measure(constraints)
                        val visibleHeight = (bar.height - ime.getBottom(this)).coerceAtLeast(0)
                        layout(bar.width, visibleHeight) { bar.placeRelative(0, 0) }
                    }
                } else Modifier
                NavigationSuite(modifier = modifier, layoutType = layoutType) {
                    Tab.entries.forEach { t ->
                        item(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            },
        ) {
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
