package dev.xiaoman.hands

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings

/** Maps Xiaoman's internal links to public Android APIs. All links go through Sentinel. */
object AppLinks {
    const val SCHEME = "xiaoman"
    private const val SCREENSHOT_ROUTE = "screenshot/take"
    const val SYSTEM_SCREENSHOT_URI = "$SCHEME://$SCREENSHOT_ROUTE"
    private const val MAX_LABEL = 60

    internal data class GlobalAction(val id: Int, val label: String, val labelRes: Int)

    private val globalActions = mapOf(
        "quick-settings/open" to GlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "展开快捷设置", R.string.hands_action_quick_settings),
        "recents/open" to GlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "打开最近任务", R.string.hands_action_recents),
        "notifications/open" to GlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "展开通知栏", R.string.hands_action_notifications),
        "lock-screen/lock" to GlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "锁屏", R.string.hands_action_lock),
        SCREENSHOT_ROUTE to GlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "系统截屏", R.string.hands_action_screenshot),
    )

    private val settingsPanels = mapOf(
        "internet/open" to (Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "网络设置面板"),
        "volume/open" to (Settings.Panel.ACTION_VOLUME to "音量设置面板"),
        "display/open" to (Settings.ACTION_DISPLAY_SETTINGS to "显示设置"),
        "bluetooth/open" to (Settings.ACTION_BLUETOOTH_SETTINGS to "蓝牙设置"),
    )

    val operatorNotes: String = buildString {
        appendLine("目标匹配时优先用 deeplink 调用以下 Android 入口。")
        globalActions.forEach { (path, route) -> appendLine("- xiaoman://$path：${route.label}") }
        settingsPanels.forEach { (path, route) -> appendLine("- xiaoman://$path：打开${route.second}，不改变设置") }
        appendLine("- xiaoman://alarm/set?hour=7&minute=0：打开预填闹钟页")
        appendLine("- xiaoman://timer/set?seconds=300：打开预填计时器")
        appendLine("入口执行失败时重新观察界面，换用系统控件；不要重复执行已被系统接受的全局动作。")
        appendLine("系统接受截屏请求后会结束本轮操作。若截屏后还需完成其他步骤，在 deeplink 动作中设置 continueAfterScreenshot=true。")
        append("只有确认图片已保存才报告保存成功。闹钟和计时器需确认页面是否已保存或启动。")
    }

    fun operatorNotes(english: Boolean): String = if (english) """
        Prefer these Android deeplinks when the goal matches:
        - xiaoman://quick-settings/open: open Quick Settings
        - xiaoman://recents/open: open Recents
        - xiaoman://notifications/open: open notifications
        - xiaoman://lock-screen/lock: lock the screen
        - xiaoman://screenshot/take: request a system screenshot
        - xiaoman://internet/open and xiaoman://volume/open: open system panels
        - xiaoman://display/open and xiaoman://bluetooth/open: open Settings pages
        - xiaoman://alarm/set?hour=7&minute=0: open a prefilled alarm
        - xiaoman://timer/set?seconds=300: open a prefilled timer
        If a route fails, observe the screen and use the available controls. Avoid repeating accepted global actions.
        Acceptance of a screenshot request ends the operator run. Set continueAfterScreenshot=true on its deeplink action only when more steps are needed.
        Verify any saved screenshot, alarm or started timer before claiming completion.
    """.trimIndent() else operatorNotes

    internal fun globalActionFor(uri: Uri): GlobalAction? = route(uri)?.let { globalActions[it] }

    /** Returns null for external links. Unknown Xiaoman links fail instead of leaving the app. */
    internal fun intentFor(uri: Uri): Intent? {
        val path = route(uri) ?: return null
        settingsPanels[path]?.let { return Intent(it.first) }
        return when (path) {
            "alarm/set" -> setAlarm(uri)
            "timer/set" -> setTimer(uri)
            else -> throw IllegalArgumentException("不支持的小满动作：$path")
        }
    }

    private fun route(uri: Uri): String? =
        if (uri.scheme.equals(SCHEME, ignoreCase = true)) {
            "${uri.host.orEmpty()}${uri.path.orEmpty()}".trimEnd('/')
        } else null

    private fun setAlarm(uri: Uri): Intent {
        val hour = uri.getQueryParameter("hour")?.trim()?.toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw IllegalArgumentException("xiaoman://alarm/set 需要 hour=0..23")
        val minute = (uri.getQueryParameter("minute")?.trim()?.ifEmpty { null } ?: "0").toIntOrNull()?.takeIf { it in 0..59 }
            ?: throw IllegalArgumentException("xiaoman://alarm/set 的 minute 需要 0..59")
        return clockIntent(AlarmClock.ACTION_SET_ALARM, uri)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
    }

    private fun setTimer(uri: Uri): Intent {
        val seconds = uri.getQueryParameter("seconds")?.toIntOrNull()?.takeIf { it in 1..86_400 }
            ?: throw IllegalArgumentException("xiaoman://timer/set 需要 seconds=1..86400")
        return clockIntent(AlarmClock.ACTION_SET_TIMER, uri).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
    }

    private fun clockIntent(action: String, uri: Uri): Intent =
        Intent(action).putExtra(AlarmClock.EXTRA_SKIP_UI, false).apply {
            uri.getQueryParameter("label")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.take(MAX_LABEL)) }
        }
}
