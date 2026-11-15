package dev.xiaoman.agent

import dev.xiaoman.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Tool definitions offered to the chat model. */
object AgentTools {
    const val MEMORY_SEARCH = "memory_search"
    const val MEMORY_READ = "memory_read"
    const val MEMORY_WRITE = "memory_write"
    const val MEMORY_FORGET = "memory_forget"
    const val LIST_SKILLS = "list_skills"
    const val SKILL_WRITE = "skill_write"
    const val SKILL_DELETE = "skill_delete"
    const val REMINDER_SET = "reminder_set"
    const val PHONE_TASK = "phone_task"
    const val INSPECT_SCREEN = "inspect_screen"
    const val HTTP_REQUEST = "http_request"

    /** Tools only offered when a writable [dev.xiaoman.api.SkillStore] is available. */
    val SKILL_TOOL_NAMES = setOf(SKILL_WRITE, SKILL_DELETE)

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            MEMORY_SEARCH, "在用户的记忆笔记中全文搜索（支持中文）。",
            schema(listOf("query"), "query" to str("搜索词"), "limit" to int("最多返回条数，默认 5")),
        ),
        ToolSpec(
            MEMORY_READ, "读取一条记忆笔记的全文。",
            schema(listOf("identifier"), "identifier" to str("笔记的 permalink（如 places/家）或标题")),
        ),
        ToolSpec(
            MEMORY_WRITE,
            "新建或更新一条记忆笔记；已有同名笔记时追加观察和关系（相同内容不会重复）。",
            schema(
                listOf("title", "observations"),
                "title" to str("笔记标题，如 家"),
                "permalink" to str("可选，路径形式的唯一键，如 places/家；默认用标题"),
                "type" to str("可选，笔记类型，如 place、person、preference；默认 note"),
                "observations" to arr(
                    "观察条目",
                    schema(
                        listOf("category", "content"),
                        "category" to str("类别，如 address、preference"),
                        "content" to str("内容"),
                        "tags" to arr("标签，不带 #", str("标签")),
                        "context" to str("可选，来源或补充说明"),
                    ),
                ),
                "relations" to arr(
                    "可选，与其他笔记的关系",
                    schema(listOf("type", "target"), "type" to str("关系类型，如 lives_in"), "target" to str("目标笔记标题")),
                ),
            ),
        ),
        ToolSpec(
            MEMORY_FORGET, "删除一条记忆笔记（真正删除文件）。仅在用户明确要求忘记时使用。",
            schema(listOf("identifier"), "identifier" to str("笔记的 permalink 或标题")),
        ),
        ToolSpec(LIST_SKILLS, "列出所有可用的手机操作技能。", schema(emptyList())),
        ToolSpec(
            SKILL_WRITE,
            "把一个可复用的操作流程保存为技能，写入手机的技能目录（保存前会请用户确认）。" +
                "skill_md 是完整的 SKILL.md 文本：以 --- 开头的 YAML frontmatter，必填 name（只允许小写字母、数字、连字符）" +
                "和 description（一行中文，写清做什么、用哪个 App）；可选 apps（包名列表）、channel" +
                "（gui、deeplink、deeplink-then-gui、chat 等，默认 gui）、confirm_before（需要用户确认的按钮文字）、" +
                "memory（要引用的记忆 permalink）、deeplinks（名称到 scheme:// 链接的映射）；用另一行 --- 结束 frontmatter，" +
                "之后是给操作模型的简短中文步骤。校验失败时工具结果会给出具体错误，按错误修正后重新调用，不要把密码或密钥写进技能。",
            schema(
                listOf("skill_md"),
                "skill_md" to str("完整的 SKILL.md 文件内容，包含 frontmatter 和正文"),
                "flow_yaml" to str("可选，flow.yaml 固定步骤文本"),
            ),
        ),
        ToolSpec(
            SKILL_DELETE,
            "删除一个由用户创建的本地技能（内置技能无法删除，删除前会请用户确认）。",
            schema(listOf("name"), "name" to str("要删除的技能名，即 SKILL.md 里的 name")),
        ),
        ToolSpec(
            REMINDER_SET,
            "设置一条定时提醒：到点后小满会在手机上弹出通知。",
            schema(
                listOf("text", "at"),
                "text" to str("提醒内容，如 喝水、带伞"),
                "at" to str("触发时间，ISO-8601 本地时间（设备时区，不带时区后缀），如 2026-11-03T08:00"),
            ),
        ),
        ToolSpec(
            PHONE_TASK, "在用户的手机上完成一个操作任务，返回结果摘要。",
            schema(
                listOf("goal"),
                "goal" to str("具体目标，包含关键参数，如 用高德打车回家（北京市海淀区某某路 1 号），选快车"),
                "skill" to str("可选，使用的技能名"),
            ),
        ),
        ToolSpec(
            INSPECT_SCREEN, "只读查看当前手机屏幕（截图与无障碍文字），不点击、不操作、不保存截图。用户问‘这是什么’、‘屏幕上有什么’等指当前屏幕的问题时使用。",
            schema(emptyList()),
        ),
        ToolSpec(
            HTTP_REQUEST,
            "发起一次 HTTP/HTTPS 请求并返回状态码、响应体和响应头。可指定请求方法、请求头和 JSON 请求体；响应体过长会被截断。",
            schema(
                listOf("url"),
                "method" to methodEnum(),
                "url" to str("完整的 http/https 请求地址"),
                "headers" to obj("可选，请求头，名称到字符串值的映射，如 Authorization、Content-Type"),
                "body" to any("可选，JSON 请求体（对象、数组或字面量）；GET/HEAD 一般不用"),
            ),
        ),
    )

    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
    private fun int(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
    private fun arr(description: String, items: JsonObject) = buildJsonObject {
        put("type", "array"); put("description", description); put("items", items)
    }
    private fun obj(description: String) = buildJsonObject { put("type", "object"); put("description", description) }
    private fun any(description: String) = buildJsonObject { put("description", description) }

    private fun methodEnum(): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", "请求方法，默认 GET")
        putJsonArray("enum") { listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { add(it) } }
    }
}
