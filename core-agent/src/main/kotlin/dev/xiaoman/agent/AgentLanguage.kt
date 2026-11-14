package dev.xiaoman.agent

/** Language of user-facing text. Internal instructions and action JSON stay the same. */
enum class AgentLanguage {
    CHINESE, ENGLISH;

    internal fun text(chinese: String, english: String): String = if (this == ENGLISH) english else chinese

    internal fun chatInstruction(): String = text(
        "除非用户明确要求使用其他语言，否则用简体中文回复；phone_task 的 goal 也用简体中文，保留原有应用名、界面文字和用户指定的输入内容。",
        "Reply in English unless the user explicitly requests another language. Write the phone_task goal in the reply language, preserving exact app names, UI labels, and text the user wants entered.",
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
