package dev.xiaoman.app.ui

import dev.xiaoman.app.session.ChatItem
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Fold only adjacent diagnostic entries. Questions, failures and replies always remain visible. */
internal sealed class TimelineRow {
    abstract val id: Long
    data class Message(val item: ChatItem, val timestamp: Instant? = null) : TimelineRow() { override val id = item.id }
    data class Process(val entries: List<ChatItem.Progress>) : TimelineRow() { override val id = entries.first().id }
}

private val MESSAGE_GAP = TimeUnit.MINUTES.toMillis(5)

internal fun chatTimeline(items: List<ChatItem>, zone: ZoneId = ZoneId.systemDefault()): List<TimelineRow> = buildList {
    val pending = mutableListOf<ChatItem.Progress>()
    fun flush() {
        if (pending.isNotEmpty()) { add(TimelineRow.Process(pending.toList())); pending.clear() }
    }
    var previousMessageTime: Instant? = null
    items.forEach { item ->
        if (item is ChatItem.Progress) {
            pending += item
        } else {
            flush()
            val sentAt = when (item) {
                is ChatItem.User -> item.sentAt
                is ChatItem.Assistant -> item.sentAt
                else -> null
            }?.let(Instant::ofEpochMilli)
            val previous = previousMessageTime
            val showTime = sentAt != null && (
                previous == null ||
                    previous.atZone(zone).toLocalDate() != sentAt.atZone(zone).toLocalDate() ||
                    sentAt.toEpochMilli() - previous.toEpochMilli() >= MESSAGE_GAP
                )
            add(TimelineRow.Message(item, sentAt.takeIf { showTime }))
            if (sentAt != null) previousMessageTime = sentAt
        }
    }
    flush()
}
