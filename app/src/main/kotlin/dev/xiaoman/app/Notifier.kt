package dev.xiaoman.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.xiaoman.app.session.PendingApproval
import dev.xiaoman.app.session.PendingQuestion

/** System notifications for agent work, questions, results and reminders. */
class Notifier(private val context: Context, private val localizedContext: () -> Context = { context }) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, localizedContext().getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = localizedContext().getString(R.string.channel_results_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, localizedContext().getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = localizedContext().getString(R.string.channel_status_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, localizedContext().getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH).apply {
                description = localizedContext().getString(R.string.channel_attention_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, localizedContext().getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = localizedContext().getString(R.string.channel_reminders_description)
            },
        )
    }

    fun status(text: String?): Notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_stat_xiaoman)
        .setContentTitle(localizedContext().getString(R.string.notification_running))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .addAction(0, localizedContext().getString(R.string.chat_stop), action(NotificationActionReceiver.ACTION_STOP, null))
        .build()

    /** Refreshes the foreground notification posted by [AgentService]. */
    fun updateStatus(text: String?) {
        if (allowed()) manager.notify(ID_STATUS, status(text))
    }

    fun showApproval(pending: PendingApproval?) {
        if (pending == null) return manager.cancel(ID_APPROVAL)
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(localizedContext().getString(R.string.approval_title))
            .setContentText(pending.request.prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.request.prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(0, localizedContext().getString(R.string.approval_once), action(NotificationActionReceiver.ACTION_APPROVE_ONCE, pending.id))
            .addAction(0, localizedContext().getString(R.string.approval_task), action(NotificationActionReceiver.ACTION_APPROVE_TASK, pending.id))
            .addAction(0, localizedContext().getString(R.string.approval_deny), action(NotificationActionReceiver.ACTION_DENY, pending.id))
            .build()
        manager.notify(ID_APPROVAL, notification)
    }

    fun showQuestion(pending: PendingQuestion?) {
        if (pending == null) return manager.cancel(ID_QUESTION)
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(localizedContext().getString(if (pending.takeover) R.string.chat_takeover_label else R.string.notification_question))
            .setContentText(pending.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(ID_QUESTION, notification)
    }

    fun showResult(text: String) {
        if (!allowed()) return
        val summary = text.trim()
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(localizedContext().getString(R.string.notification_result))
            .setContentText(summary.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp(ACTION_OPEN_CHAT))
            .build()
        manager.notify(TAG_RESULT, 0, notification)
    }

    fun dismissResult() = manager.cancel(TAG_RESULT, 0)

    /** Heads-up notification posted when a reminder_set alarm fires. */
    fun showReminder(id: Int, text: String) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(localizedContext().getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(id, notification)
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(action: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, approvalId: Long?): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        if (approvalId != null) intent.putExtra(NotificationActionReceiver.EXTRA_APPROVAL_ID, approvalId)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_OPEN_CHAT = "dev.xiaoman.app.OPEN_CHAT"
        private const val TAG_RESULT = "agent-result"
        private const val CHANNEL_RESULTS = "agent-results"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_REMINDERS = "reminders"
        const val ID_STATUS = 1
        const val ID_APPROVAL = 2
        const val ID_QUESTION = 3
    }
}
