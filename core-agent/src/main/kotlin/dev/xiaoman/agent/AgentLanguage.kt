package dev.xiaoman.agent

/** Language of user-facing text. Internal instructions and action JSON stay the same. */
enum class AgentLanguage {
    CHINESE, ENGLISH;

    internal fun text(chinese: String, english: String): String = if (this == ENGLISH) english else chinese

    internal fun chatInstruction(): String = text(
        "除非用户明确要求使用其他语言，否则用简体中文回复；phone_task 的 goal 也用简体中文，保留原有应用名、界面文字和用户指定的输入内容。意图路由：用户要截屏、截图并保存/分享图片时，用 phone_task 请求系统截图 API；用户问‘这是什么’、‘屏幕上有什么’或用‘这个/这里’指当前手机画面时，先调用 inspect_screen 只读查看当前屏幕再回答，不要因此保存截图；用户要求点击、打开、输入、设置等改变手机界面的动作时用 phone_task。若指代不清且无法从上下文判断是否指当前屏幕，先简短澄清。",
        "Reply in English unless the user explicitly requests another language. Write the phone_task goal in the reply language, preserving exact app names, UI labels, and text the user wants entered. Route intent carefully: when the user asks to capture, save, or share a screenshot, use phone_task to request the system screenshot API; when they ask what something is, what is on screen, or use “this/here” to refer to the current phone screen, call inspect_screen first and answer from its read-only result without saving a screenshot; use phone_task for actions that change the UI, such as tapping, opening, typing, or changing settings. If the referent is ambiguous and context does not clarify whether it means the current screen, ask a brief question.",
    )

    internal fun operatorInstruction(): String = text(
        "面向用户的 question 和 summary 用简体中文，应用名、目标控件文字和要输入的内容保持原文。",
        "Write user-facing question and summary fields in English. Preserve original app names, target UI labels, and text to enter.",
    )

    internal fun secureScreen(): String = text(
        Prompts.SECURE_SCREEN,
        "Android protects this screen. Please complete this step yourself, then reply here to continue.",
    )
}
