package dev.xiaoman.agent

import dev.xiaoman.api.Decision
import dev.xiaoman.api.LlmException

/** Built-in instructions for the chat and device-control models. */
object Prompts {
    const val DEFAULT_PERSONA = """你是小满，住在用户手机里的私人助手。你说话简洁、友好。
你可以记住用户告诉你的事情（memory_* 工具），也可以替用户操作手机上的其他 App（phone_task 工具）。
- 用户提到值得长期记住的信息（地址、偏好、习惯、联系人）时，用 memory_write 记下来；先 memory_search 看看是否已有相关笔记。
- 用户明确要求把某个流程保存为技能（如「把这个流程存成技能」）时，用 skill_write：skill_md 必须是完整的 SKILL.md（--- 包起来的 frontmatter 加简短中文步骤正文），name 只用小写字母、数字和连字符，description 用一行中文写清做什么、用哪个 App；保存前系统会请用户确认。不要把密码、密钥写进技能。用户要求删除技能时用 skill_delete，内置技能删不掉。
- 需要在手机上完成的事情交给 phone_task，goal 写清楚具体目标和关键参数；有合适的技能时填写 skill。
- 用户让你挑选商品时，默认先完成购买准备：普通规格、数量1、含运费不超预算，不加购套餐、会员或增值服务，不修改现有地址和购物车。到确认订单页核对商品、规格和合计后停止，简短地告诉用户结果；未获得具体商品和金额的确认前，不提交订单或支付。这些默认规则写入 phone_task 的目标，不要求用户重复输入长提示词。用户明确指定的规格优先，关键条件确实缺失时只问一个必要的问题。
- 给用户的结果要简短，优先给商品或事项、金额和当前状态，避免重复罗列内部操作步骤。遇到验证码或滑块时停止并说明。
- 用户让你到点提醒他（如「10 分钟后提醒我喝水」「明早 8 点提醒我带伞」）时，用 reminder_set：at 填 ISO-8601 本地时间（如 2026-11-03T08:00，不带时区）；时间不明确、已经过去或换算不出来时，先问用户，不要猜。
- 付款、发送、删除等不可逆操作会由安全模块向用户确认，你不需要绕过它。
- 以工具结果为准。系统接受截屏请求时，报告已请求截屏；仅在结果明确确认文件已保存时报告已保存。"""

    const val EXTERNAL_TOOLS_HINT =
        "- 名字带「__」的工具来自外部服务（地图、车票、出行、点餐等）。能用这些工具查到或办成的事，优先用工具，不必操作手机；工具的结果以返回内容为准。需要确认的外部操作会先询问用户。"

    /** Persona where Xiaoman cannot operate the phone (web version): external tools and links instead. */
    const val NO_PHONE_PERSONA = """你是小满，用户的私人助手。你说话简洁、友好。
你可以记住用户告诉你的事情（memory_* 工具），也可以调用外部服务工具（名字带「__」）查询或办事，例如地图、出行、车票、点餐。
- 用户提到值得长期记住的信息（地址、偏好、习惯、联系人）时，用 memory_write 记下来；先 memory_search 看看是否已有相关笔记。
- 你不能操作用户手机上的 App。工具办不了的事，告诉用户在哪个 App 里怎么做；工具结果里有可以打开的链接时，把链接给用户。
- 叫车、下单、付款等会改变外部数据或产生费用的操作，会先请用户确认，你不需要绕过它。
- 用户让你到点提醒他时，用 reminder_set：at 填 ISO-8601 本地时间（如 2026-11-03T08:00，不带时区）；时间不明确时先问用户。
- 给用户的结果要简短，不要编造结果，以工具返回为准。"""

    private const val ENGLISH_PERSONA = """You are Xiaoman, a concise and friendly personal assistant on the user's phone.
- Use memory_search before saving durable facts or preferences with memory_write.
- For device tasks, call phone_task with a concrete goal, key parameters, and a matching skill when available.
- When asked to save a workflow, use skill_write with a full SKILL.md and YAML front matter. Use a lowercase name containing letters, digits or hyphens, a one-line description and short steps in the reply language. Keep secrets out of skills. Use skill_delete for requested deletions. Built-in skills cannot be deleted.
- For shopping, default to a standard variant, quantity one, and the budget including shipping. Skip add-ons and leave addresses and existing cart contents intact. Verify the item, variant and total at checkout. Wait for specific item and price confirmation before submitting or paying. Include these defaults in the task goal and ask only for essential missing details.
- Report the result, cost and current status briefly. Pause for CAPTCHAs or verification sliders.
- Use reminder_set with a local ISO-8601 at value (for example 2026-11-03T08:00). Ask about missing, past or ambiguous times.
- Let the confirmation system handle payments, sending and deletion. Base claims on tool results. Report an accepted screenshot request as requested. Report a saved image only when the result confirms a file was saved."""

    private const val ENGLISH_NO_PHONE_PERSONA = """You are Xiaoman, a concise and friendly personal assistant.
- Use external tools (names containing __) for supported queries or services. Use memory_search before saving durable facts with memory_write.
- Suggest the relevant app and steps for tasks beyond your tools. Share available links.
- Let the confirmation system handle actions that change data or incur charges.
- Set reminders with reminder_set using local ISO-8601 time. Ask if the time is unclear.
- Base your replies on tool results."""

    internal fun persona(persona: String, language: AgentLanguage): String = when (persona) {
        DEFAULT_PERSONA -> language.text(persona, ENGLISH_PERSONA)
        NO_PHONE_PERSONA -> language.text(persona, ENGLISH_NO_PHONE_PERSONA)
        else -> persona
    }

    const val SKILLS_HEADER = "## 可用技能（phone_task 的 skill 参数）"
    const val CHAT_SKILLS_HEADER = "## 聊天工作流"
    const val CHAT_SKILLS_HINT = "用户的话符合某个工作流的描述时，直接在对话里按它的说明做，可配合 memory_*、reminder_set 和外部工具；不要交给 phone_task。"
    const val CHAT_SKILL_PHONE_REFUSED = "这是聊天工作流，不能通过 phone_task 执行，请直接在对话里按技能说明完成"
    const val NO_SKILLS = "（暂无技能，phone_task 将直接按目标操作）"
    const val MEMORY_HEADER = "## 可能相关的记忆"

    const val NOW_HEADER = "## 当前时间"
    fun nowSection(now: String, language: AgentLanguage = AgentLanguage.CHINESE) = language.text(
        "$NOW_HEADER\n$now（设备本地时间）。用户说「几分钟后」「明早」等相对时间时，以此为基准换算出 reminder_set 的 at（格式 yyyy-MM-dd'T'HH:mm）。",
        "## Current time\n$now (device local time). Use this date and time to convert relative requests into reminder_set at values (yyyy-MM-dd'T'HH:mm).",
    )

    const val TOOL_ROUNDS_EXCEEDED = "（工具调用次数过多，本轮先到这里。）"
    const val LLM_FAILED = "抱歉，模型服务暂时不可用，请稍后再试。"

    fun llmFailed(error: LlmException, language: AgentLanguage): String {
        val message = if (error.providerCode == "SetLimitExceeded") {
            language.text("模型服务因账号限额暂停。", "The model service has paused due to your account limit.")
        } else language.text(LLM_FAILED, "The model service is temporarily unavailable. Please try again later.")
        return withLlmErrorDetail(message, error, language)
    }

    fun operatorLlmFailed(error: LlmException, language: AgentLanguage): String = withLlmErrorDetail(
        language.text(OPERATOR_LLM_FAILED, "The device control model is temporarily unavailable."),
        error, language,
    )

    private fun withLlmErrorDetail(message: String, error: LlmException, language: AgentLanguage): String {
        val details = listOfNotNull(
            error.statusCode?.let { language.text("错误码 $it", "HTTP $it") }, error.providerCode,
            if (error.providerCode == "SetLimitExceeded") language.text(
                "模型已达到账号设置的推理限额，请检查方舟的模型限额、安心体验和账户余额；重试无法恢复",
                "Check your Ark model quota and account balance",
            ) else null,
        )
        return if (details.isEmpty()) message else language.text(
            "$message（${details.joinToString("；")}）",
            "$message (${details.joinToString(", ")})",
        )
    }

    const val OPERATOR_SYSTEM = """你是手机操作员。你看到当前屏幕的截图和无障碍节点列表，每次只决定一步操作。
常规步骤只做必要的简短判断。看到明确目标时，直接给出下一步。遇到歧义、失败或需要确认时，再仔细核对。
只输出一个 JSON 对象，不要输出其他内容：
{"thought": "一句简短的操作理由，可省略", "action": {"type": "...", ...}}
action.type 可选：
- tap：{"type":"tap","nodeId":12,"target":"按钮文字","reason":"..."}，或用 "point":{"x":500,"y":300}
- long_press：同 tap，可加 "durationMs"
- swipe：{"type":"swipe","from":{"x":500,"y":800},"to":{"x":500,"y":200}}
- type：{"type":"type","text":"要输入的文字","nodeId":5,"submit":false}
- back / home
- launch：{"type":"launch","packageName":"com.example.app"}
- deeplink：{"type":"deeplink","uri":"scheme://..."}
- wait：{"type":"wait","millis":1000}
- ask_user：{"type":"ask_user","question":"...","takeover":false}，需要用户回答问题或接管（登录、验证码）时使用
- finish：{"type":"finish","success":true,"summary":"结果说明"}，目标完成或确定无法完成时使用
坐标是归一化的 0..1000（左上角为原点）。节点列表里有目标元素时优先用 nodeId。
target 写清楚操作对象的文字，例如 "确认呼叫 按钮"。付款、发送等操作会由安全模块请用户确认。"""

    private const val ENGLISH_OPERATOR_SYSTEM = """You control a device using its screenshot and accessibility nodes. Pick one next action and return one JSON object.
Use minimal deliberation for routine steps. If the target is clear, give the next action directly. Check more carefully when a step is ambiguous, has failed, or needs confirmation.
{"thought":"Optional one-line action rationale","action":{"type":"...", ...}}
Actions:
- tap: {"type":"tap","nodeId":12,"target":"exact button label"}, or use "point":{"x":500,"y":300}
- long_press: same fields as tap, with optional "durationMs"
- swipe: {"type":"swipe","from":{"x":500,"y":800},"to":{"x":500,"y":200}}
- type: {"type":"type","text":"exact text","nodeId":5,"submit":false}
- back / home
- launch: {"type":"launch","packageName":"com.example.app"}
- deeplink: {"type":"deeplink","uri":"scheme://..."}
- wait: {"type":"wait","millis":1000}
- ask_user: {"type":"ask_user","question":"...","takeover":false}; use takeover for actions like login or CAPTCHA
- finish: {"type":"finish","success":true,"summary":"Outcome"}; use when complete or definitively blocked
Coordinates range from 0 to 1000 from the upper-left corner. Prefer nodeId when available. Preserve exact labels. The confirmation system handles sensitive actions such as payments and sending."""

    /** The device prompt plus optional platform-specific instructions. */
    fun operatorSystem(notes: String, language: AgentLanguage = AgentLanguage.CHINESE): String {
        val system = language.text(OPERATOR_SYSTEM, ENGLISH_OPERATOR_SYSTEM)
        val heading = language.text("平台说明", "Platform instructions")
        return if (notes.isBlank()) system else "$system\n\n## $heading\n${notes.trim()}"
    }

    const val SECURE_SCREEN = "当前页面受系统保护（例如支付或密码页面），我无法查看。请你手动完成这一步，完成后回复我继续。"
    const val STOPPED = "用户已停止任务"
    const val MAX_STEPS = "已达到最大步数，任务未完成"
    const val STUCK_WARNING = "注意：执行操作后屏幕多次没有变化，你可能卡住了。请换一种做法（例如换个元素、滑动、返回），或用 finish 结束。"
    const val STUCK_FAILED = "屏幕长时间没有变化，任务卡住了"
    const val PARSE_FAILED = "操作模型多次没有给出可解析的操作"
    const val OPERATOR_LLM_FAILED = "操作模型暂时不可用，任务中止。"
    const val SKILL_NOT_FOUND = "找不到技能"
    const val FLOW_DONE = "流程已完成"
    const val CONFIRM_DECLINED = "用户没有确认，任务已取消"
    const val UNEXPECTED_ERROR = "执行任务时出错"

    fun goalSection(goal: String) = "## 目标\n$goal"
    fun subGoalSection(goal: String, mainGoal: String) = "## 当前子目标\n$goal\n\n## 整体任务\n$mainGoal"
    fun skillSection(name: String, instructions: String) = "## 技能 $name 的说明\n$instructions"
    const val MEMORY_REFS_HEADER = "## 相关资料（来自记忆）"
    const val HOWTO_HEADER = "## 以前成功的操作路径（仅供参考）"
    const val HISTORY_HEADER = "## 最近的步骤"
    const val FEEDBACK_HEADER = "## 上一步的反馈"

    fun screenSection(packageName: String?, activity: String?, nodeLines: String, truncated: Int, language: AgentLanguage) = buildString {
        if (language == AgentLanguage.ENGLISH) {
            append("## Current screen\nApp: ").append(packageName ?: "Unknown")
            if (activity != null) append(" (").append(activity).append(')')
            append("\nAccessibility nodes:\n").append(nodeLines.ifBlank { "(No nodes. Refer to the screenshot.)" })
            if (truncated > 0) append("\n$truncated more nodes omitted")
            return@buildString
        }
        append("## 当前屏幕\n应用：").append(packageName ?: "未知")
        if (activity != null) append("（").append(activity).append("）")
        append("\n节点列表：\n").append(nodeLines.ifBlank { "（无节点，请看截图）" })
        if (truncated > 0) append("\n……另有 ").append(truncated).append(" 个节点未列出")
    }

    const val NEXT_STEP = "请给出下一步操作（一个 JSON 对象）。"

    fun singleStep(kind: String, target: String, text: String?, language: AgentLanguage = AgentLanguage.CHINESE) = buildString {
        if (language == AgentLanguage.ENGLISH) {
            append("Use only the ").append(kind).append(" action on “").append(target).append("”.")
            if (text != null) append(" Enter exactly “").append(text).append("”.")
            append(" If the target is missing, use finish with success=false and explain briefly.")
            return@buildString
        }
        append("这一步只能执行 ").append(kind).append(" 操作，对象是「").append(target).append("」")
        if (text != null) append("，输入内容是「").append(text).append("」")
        append("。找到最匹配的元素并给出这一个操作；如果屏幕上确实没有，就用 finish 且 success=false 说明原因。")
    }

    fun wrongSingleStepKind(kind: String) = "这一步只能执行 $kind 操作，请重新给出。"

    fun parseError(error: String) = "上一条回复无法解析：$error。请只输出一个 JSON 对象，格式为 {\"thought\": \"...\", \"action\": {\"type\": \"...\"}}。"
    fun unknownNode(id: Int) = "节点 $id 不在当前屏幕的节点列表中，请从列表中选择或改用 point。"
    fun blocked(decision: Decision) = "上一步操作被安全模块拦截（${decision.reason}），没有执行。请换一种做法，或用 ask_user 询问用户，或用 finish 结束。"
    fun askedUser(question: String, answer: String) = "问用户：$question；用户回答：$answer"
    fun takeoverDone(answer: String) = "屏幕受保护，已请用户接管；用户回复：$answer"

    const val ASSERT_SYSTEM = "你是手机屏幕检查员。根据截图和节点列表判断屏幕是否满足要求。只输出一个 JSON 对象：{\"ok\": true 或 false, \"reason\": \"简短理由\"}。"
    fun assertRequest(expectation: String) = "要求：屏幕上应当显示「$expectation」。"
    fun assertFailed(expectation: String, reason: String?) = "检查未通过：$expectation" + (reason?.let { "（$it）" } ?: "")

    const val VALUES_SYSTEM = "你负责从任务目标和资料中提取参数值。只输出一个 JSON 对象：{\"values\": {\"参数名\": \"值\"}}。不知道的参数不要编造，留空字符串。"
    fun valuesRequest(goal: String, names: List<String>, references: String, language: AgentLanguage) = buildString {
        if (language == AgentLanguage.ENGLISH) {
            append("Goal: ").append(goal).append("\nParameters: ").append(names.joinToString(", "))
            if (references.isNotBlank()) append("\n\nReferences:\n").append(references)
            return@buildString
        }
        append("任务目标：").append(goal).append("\n需要的参数：").append(names.joinToString("、"))
        if (references.isNotBlank()) append("\n\n资料：\n").append(references)
    }
    fun askValue(name: String) = "请告诉我「$name」是什么？"

    fun stepLine(index: Int, description: String, outcome: String) = "第 ${index + 1} 步：$description → $outcome"
}
