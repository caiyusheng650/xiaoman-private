package dev.xiaoman.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Xiaoman design tokens. Every screen reads spacing, card shape and content width from here,
 * so changing a number in one place re-aligns the whole app instead of hunting dp literals.
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

object Dimens {
    /** Same left/right inset on every page. */
    val pageHorizontal = Space.lg
    /** Gap between neighbouring cards on list pages. */
    val cardGap = 10.dp
    val cardPadding = 14.dp
    val cardShape = 16.dp
    /** Reading column on tablets: content never stretches edge to edge on a wide screen. */
    val maxContentWidth = 640.dp
    /** Chat bubbles stay readable on large screens even though they may fill a phone. */
    val maxBubbleWidth = 560.dp
}

/**
 * Cap a page's content column and keep it centred on wide screens.
 * Order matters: widthIn must come first so fillMaxWidth fills the *capped* constraint,
 * otherwise fillMaxWidth locks the width to the whole window and the cap is ignored.
 */
fun Modifier.pageWidth(): Modifier = this.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()

/**
 * The one header every tab page uses: fixed above the scrolling body (it never scrolls away),
 * a semi-bold title with an optional one-line subtitle and a trailing action slot.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.pageWidth().padding(start = Dimens.pageHorizontal, end = Space.sm, top = Space.md, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        action?.invoke()
    }
}

/** Small grey label that divides a page into sections (service categories, etc.). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Space.md, bottom = Space.xs),
    )
}

/** Card with the single border/shape/colour convention used across all pages. */
@Composable
fun StandardCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardShape),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Dimens.cardPadding), content = content)
    }
}

/**
 * Pinned action bar shared by Settings and Services: identical divider, inset and height on
 * both pages, with the content column capped like the page body on tablets.
 */
@Composable
fun BottomActionBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.pageWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Centred width-capped body column, the root wrapper every full page shares. */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.pageWidth(), content = content)
    }
}

/** Inset used by scrolling bodies and lazy lists. */
val pageContentPadding
    @Composable get() = androidx.compose.foundation.layout.PaddingValues(
        start = Dimens.pageHorizontal,
        end = Dimens.pageHorizontal,
        top = Space.sm,
        bottom = Space.lg,
    )

/**
 * Short-lived page status: "已保存" and friends. Success clears itself quickly, an error stays
 * long enough to be read. The text is kept after hiding so the exit animation still has content.
 */
class PageFeedbackState internal constructor(private val scope: CoroutineScope) {
    var visible by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var isError by mutableStateOf(false)
        private set

    private var timer: Job? = null

    fun show(message: String, isError: Boolean = false) {
        this.message = message
        this.isError = isError
        visible = true
        timer?.cancel()
        timer = scope.launch {
            delay(if (isError) ERROR_HOLD_MS else SUCCESS_HOLD_MS)
            visible = false
        }
    }

    private companion object {
        const val SUCCESS_HOLD_MS = 1_800L
        const val ERROR_HOLD_MS = 4_000L
    }
}

@Composable
fun rememberPageFeedbackState(): PageFeedbackState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PageFeedbackState(scope) }
}

/**
 * Pill floating at the bottom of a page body, just above the pinned action bar, so the save
 * button it reports on is never covered. Announced by TalkBack through a live region.
 */
@Composable
fun PageFeedback(state: PageFeedbackState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.92f) +
            slideInVertically(tween(200)) { it / 3 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 },
        modifier = modifier.semantics {
            liveRegion = if (state.isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = if (state.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (state.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, if (state.isError) MaterialTheme.colorScheme.error.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative: the text already says what happened, so nothing extra is announced.
                Icon(
                    if (state.isError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
