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

    internal data class GlobalAction(val id: Int, val labelRes: Int)
    private data class IntentRoute(val action: String, val chinese: String, val english: String)

    private val globalActions = mapOf(
        "quick-settings/open" to GlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, R.string.hands_action_quick_settings),
        "recents/open" to GlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, R.string.hands_action_recents),
        "notifications/open" to GlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, R.string.hands_action_notifications),
        "lock-screen/lock" to GlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, R.string.hands_action_lock),
        SCREENSHOT_ROUTE to GlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, R.string.hands_action_screenshot),
    )

    private val intentRoutes = mapOf(
        "internet/open" to IntentRoute(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "打开网络设置面板", "Open the Internet panel"),
        "volume/open" to IntentRoute(Settings.Panel.ACTION_VOLUME, "打开音量设置面板", "Open the volume panel"),
        "display/open" to IntentRoute(Settings.ACTION_DISPLAY_SETTINGS, "打开显示设置", "Open Display settings"),
        "bluetooth/open" to IntentRoute(Settings.ACTION_BLUETOOTH_SETTINGS, "打开蓝牙设置", "Open Bluetooth settings"),
        "alarm/set?hour=7&minute=0" to IntentRoute(AlarmClock.ACTION_SET_ALARM, "打开预填闹钟页（示例时间）", "Open a prefilled alarm (example time)"),
        "timer/set?seconds=300" to IntentRoute(AlarmClock.ACTION_SET_TIMER, "打开预填计时器（示例时长）", "Open a prefilled timer (example duration)"),
    )

    internal fun operatorNotes(
        english: Boolean,
        availableActions: Set<Int>,
        canHandle: (Intent) -> Boolean,
        actionLabel: (Int) -> String,
    ): String = buildString {
        val actions = globalActions.filterValues { it.id in availableActions }
        val intents = intentRoutes.filterValues { canHandle(Intent(it.action)) }
        if (actions.isEmpty() && intents.isEmpty()) {
            append(if (english) "No registered Android entry points are available right now." else "当前没有可用的已登记 Android 入口。")
            return@buildString
        }
        appendLine(if (english) "Available Android entry points. Prefer these when the goal matches:" else "当前可用的 Android 入口。目标匹配时优先使用：")
        actions.forEach { (path, route) -> appendLine("- $SCHEME://$path: ${actionLabel(route.labelRes)}") }
        intents.forEach { (path, route) -> appendLine("- $SCHEME://$path: ${if (english) route.english else route.chinese}") }
        appendLine(if (english) {
            "If an entry point fails, observe the screen and use its controls. Verify the actual result before claiming completion."
        } else {
            "调用失败时重新观察屏幕并使用可见控件。检查实际结果再报告完成。"
        })
        if (SCREENSHOT_ROUTE in actions) {
            append(if (english) {
                "Do not repeat an accepted screenshot. Set continueAfterScreenshot=true only when more steps are needed."
            } else {
                "截屏请求已被系统接受后不要重复。仅在还有后续步骤时设置 continueAfterScreenshot=true。"
            })
        }
    }


    internal fun globalActionFor(uri: Uri): GlobalAction? = route(uri)?.let { globalActions[it] }

    /** Returns null for external links. Unknown Xiaoman links fail instead of leaving the app. */
    internal fun intentFor(uri: Uri): Intent? {
        val path = route(uri) ?: return null
        intentRoutes[path]?.let { return Intent(it.action) }
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
