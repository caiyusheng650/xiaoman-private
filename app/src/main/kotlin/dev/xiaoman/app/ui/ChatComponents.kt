package dev.xiaoman.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.session.localizedText
import dev.xiaoman.app.session.PendingQuestion
import android.graphics.BitmapFactory
import java.io.File

@Composable
internal fun ChatHeader(ready: Boolean, busy: Boolean, attention: Boolean, onClearHistory: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.pageWidth().padding(horizontal = Dimens.pageHorizontal, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.chat_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                stringResource(when {
                    attention -> R.string.chat_waiting
                    busy -> R.string.chat_processing
                    ready -> R.string.chat_ready
                    else -> R.string.chat_setup
                }),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (onClearHistory != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.chat_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_clear_history)) },
                        onClick = {
                            menuOpen = false
                            onClearHistory()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatWelcome(onExample: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.sm), verticalArrangement = Arrangement.spacedBy(Dimens.cardGap)) {
        Text(stringResource(R.string.chat_welcome), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.chat_welcome_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.sm))
        Example(Icons.Outlined.ShoppingBag, stringResource(R.string.example_notebook), stringResource(R.string.example_notebook_hint), stringResource(R.string.example_notebook_prompt), onExample)
        Example(Icons.Outlined.Schedule, stringResource(R.string.example_water), stringResource(R.string.example_water_hint), stringResource(R.string.example_water_prompt), onExample)
        Example(Icons.Outlined.Lightbulb, stringResource(R.string.example_preference), stringResource(R.string.example_preference_hint), stringResource(R.string.example_preference_prompt), onExample)
        Text(
            stringResource(R.string.chat_welcome_type),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Example(icon: ImageVector, title: String, detail: String, prompt: String, onExample: (String) -> Unit) {
    Surface(
        onClick = { onExample(prompt) },
        shape = RoundedCornerShape(Dimens.cardShape),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(Dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.md)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun TaskStatus(status: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun QuestionCard(pending: PendingQuestion, onPick: (String) -> Unit) {
    Surface(
        modifier = Modifier.pageWidth().padding(horizontal = Dimens.pageHorizontal),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.padding(Dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                stringResource(if (pending.takeover) R.string.chat_takeover_label else R.string.notification_question),
                style = MaterialTheme.typography.labelLarge,
            )
            key(pending.id) {
                Text(
                    pending.question,
                    modifier = Modifier.heightIn(max = 112.dp).verticalScroll(rememberScrollState())
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (pending.options.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        pending.options.forEachIndexed { index, option ->
                            if (index == 0) {
                                Button(onClick = { onPick(option) }) { Text(option) }
                            } else {
                                OutlinedButton(onClick = { onPick(option) }) { Text(option) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pill floating over the transcript once the reader scrolls away from the newest row; one tap glides back. */
@Composable
internal fun JumpToLatest(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f),
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Icon(Icons.Filled.ArrowDownward, null, Modifier.size(14.dp))
                Text(stringResource(R.string.chat_latest), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun ProcessCard(entries: List<ChatItem.Progress>) {
    var expanded by rememberSaveable(entries.first().id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(Dimens.cardShape),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Dimens.cardPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ListAlt, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.chat_process_count, entries.size),
                    Modifier.weight(1f).padding(horizontal = Space.sm),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(if (expanded) R.string.chat_process_hide else R.string.chat_process_show),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    Modifier.padding(horizontal = Dimens.cardPadding).padding(bottom = Dimens.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    entries.forEach {
                        Text(
                            it.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatMessage(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = Dimens.maxBubbleWidth).fillMaxWidth(0.86f).wrapContentWidth(Alignment.End),
            ) {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    item.imagePath?.let { path ->
                        val bitmap = remember(path) { File(path).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
                        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxWidth().heightIn(max = 240.dp))
                    }
                    if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is ChatItem.Assistant -> Box(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.widthIn(max = Dimens.maxBubbleWidth).fillMaxWidth(0.92f).wrapContentWidth(Alignment.Start),
            ) {
                Text(chatRichText(item.text), Modifier.padding(horizontal = Space.lg, vertical = Space.md), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is ChatItem.Question -> NoticeCard(stringResource(if (item.takeover) R.string.chat_takeover_card else R.string.chat_question_card), item.text, false)
        is ChatItem.Error -> NoticeCard(stringResource(R.string.chat_failed_card), item.localizedText(), true)
        is ChatItem.Progress -> Unit // handled by ProcessCard
    }
}

@Composable
private fun NoticeCard(title: String, body: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(Dimens.cardShape),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.cardPadding.plus(2.dp)), verticalArrangement = Arrangement.spacedBy(Space.xs.plus(2.dp))) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(chatRichText(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Basic emphasis and lists from model replies, rendered as text rather than raw Markdown markers. */
internal fun chatRichText(raw: String) = buildAnnotatedString {
    val text = raw.replace(Regex("(?m)^#{1,6}\\s+"), "").replace(Regex("(?m)^[-*]\\s+"), "• ")
    var position = 0
    Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach { match ->
        append(text.substring(position, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
        position = match.range.last + 1
    }
    append(text.substring(position))
}
