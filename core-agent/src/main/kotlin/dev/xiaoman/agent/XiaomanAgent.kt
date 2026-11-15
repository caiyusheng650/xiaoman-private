package dev.xiaoman.agent

import dev.xiaoman.api.ToolProvider
import dev.xiaoman.api.ToolSpec
import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.Device
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.ReminderScheduler
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillChannel
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.SkillStore
import dev.xiaoman.api.StopSignal
import dev.xiaoman.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The Xiaoman agent: chat turns with memory and phone tools ([handle]), and phone tasks run by
 * the operator loop or a skill flow ([runPhoneTask]). See docs/ARCHITECTURE.md.
 *
 * Conversation history is kept in memory, bounded to the last [HISTORY_LIMIT] user/assistant messages.
 */
class XiaomanAgent(
    private val chat: LlmClient,
    private val operator: LlmClient,
    private val device: Device,
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    sentinel: Sentinel,
    private val users: UserChannel,
    private val reminders: ReminderScheduler,
    private val config: AgentConfig = AgentConfig(),
    private val persona: String = Prompts.DEFAULT_PERSONA,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /** Extra tools such as MCP servers; their names must not shadow the built-in ones. */
    private val externalTools: ToolProvider? = null,
    /** Writable skill folder; when null the skill_write / skill_delete tools are not offered. */
    private val skillStore: SkillStore? = null,
    private val language: () -> AgentLanguage = { AgentLanguage.CHINESE },
    private val operatorNotes: suspend (AgentLanguage) -> String = { config.operatorNotes },
) : Agent {
    private val loop = OperatorLoop(operator, device, sentinel, users, config, operatorNotes)
    private val flows = FlowRunner(loop, users)
    private val historyLock = Mutex()
    private val history = ArrayDeque<ChatMessage>()
    /** Installed apps rendered for prompts, refreshed at most every [APPS_TTL_MS]. */
    private var appsCache: Pair<Long, String>? = null
    /** Deep links rendered for prompts, refreshed on the same schedule as [appsCache]. */
    private var schemesCache: Pair<Long, String>? = null

    override suspend fun handle(userMessage: String, listener: AgentListener, stop: StopSignal) =
        handle(userMessage, imagePng = null, listener, stop)

    override suspend fun handle(userMessage: String, imagePng: ByteArray?, listener: AgentListener, stop: StopSignal) {
        val taskId = idFactory()
        val emit: (AgentEvent) -> Unit = listener::onEvent
        val replyLanguage = language()
        val tools = ChatTools(
            memory, skills, clock, reminders, externalTools,
            phoneTasks = config.phoneTasks,
            phoneTask = { goal, skill -> runPhoneTask(goal, skill, listener, stop, replyLanguage) },
            skillStore = skillStore,
            confirmSkill = skillStore?.let { { prompt -> confirmByAsk(taskId, prompt, replyLanguage) } },
            language = replyLanguage,
            inspectScreen = {
                // The attached picture already shows what the user is asking about.
                if (imagePng != null) Prompts.userImageHint(replyLanguage)
                else inspectScreenForChat(replyLanguage, userMessage)
            },
        )
        try {
            val builtIn = builtInSpecs(replyLanguage, hasUserImage = imagePng != null)
            val toolSpecs = builtIn + externalSpecs()
            val system = ChatMessage.system(
                systemPrompt(
                    userMessage, replyLanguage,
                    hasExternalTools = toolSpecs.size > builtIn.size,
                    hasUserImage = imagePng != null,
                ),
            )
            val past = historyLock.withLock { history.toList() }
            val turn = mutableListOf(ChatMessage(ChatRole.USER, buildList {
                add(ContentPart.Text(userMessage))
                imagePng?.let { add(ContentPart.ImagePng(it)) }
            }))
            var lastText: String? = null
            var finalText: String? = null
            for (round in 0..MAX_TOOL_ROUNDS) {
                val offerTools = round < MAX_TOOL_ROUNDS
                val response = chat.complete(
                    ChatRequest(listOf(system) + past + turn, tools = if (offerTools) toolSpecs else emptyList()),
                )
                val text = response.text.trim()
                if (response.toolCalls.isEmpty() || !offerTools) {
                    finalText = text.ifEmpty { if (response.toolCalls.isNotEmpty()) replyLanguage.text(Prompts.TOOL_ROUNDS_EXCEEDED, "Too many tool calls to complete this turn.") else "" }
                    break
                }
                if (text.isNotEmpty()) {
                    emit(AgentEvent.Reply(taskId, text))
                    lastText = text
                }
                turn += ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(response.text)), toolCalls = response.toolCalls)
                for (call in response.toolCalls) {
                    turn += ChatMessage.tool(call.id, tools.execute(call, taskId, emit))
                }
                if (stop.stopped) break
            }
            if (!finalText.isNullOrEmpty()) emit(AgentEvent.Reply(taskId, finalText))
            remember(userMessage, finalText?.takeIf { it.isNotEmpty() } ?: lastText)
        } catch (e: LlmException) {
            emit(AgentEvent.Failed(taskId, Prompts.llmFailed(e, replyLanguage)))
        }
    }

    /** Uses the operator's vision-capable model for an explicit, read-only screen question. */
    private suspend fun inspectScreenForChat(language: AgentLanguage, userQuestion: String): String {
        val screen = device.inspectScreen()
        if (screen.secure) return language.text(
            "Android 将当前页面标记为受保护页面，无法读取其内容。",
            "Android marks this as a protected screen, so its contents cannot be inspected.",
        )
        val readableNodes = screen.nodes.filterNot { it.password }
        val shownNodes = readableNodes.take(80)
        val screenText = Prompts.screenSection(
            screen.packageName,
            screen.activityName,
            shownNodes.joinToString("\n") { ScreenText.nodeLine(it) },
            readableNodes.size - shownNodes.size,
            language,
        )
        val parts = buildList {
            add(ContentPart.Text(language.text("用户问题：", "User question: ") + userQuestion + "\n\n" + screenText))
            if (screen.nodes.none { it.password }) screen.screenshotPng?.let { add(ContentPart.ImagePng(it)) }
        }
        val system = language.text(
            "你是只读的手机屏幕观察员。结合用户当前问题、截图和无障碍文字，用简短自然语言说明屏幕上实际可见的内容。优先依据截图；无法辨认时明确说不确定。不要执行操作、不要编造屏幕外的信息，也不要复述密码字段。",
            "You are a read-only phone screen observer. Based on the user's current question, screenshot, and accessibility text, briefly describe what is actually visible. Prefer the screenshot; say when something is unclear. Do not perform actions, invent off-screen facts, or repeat password fields.",
        )
        val observed = operator.complete(
            ChatRequest(listOf(ChatMessage.system(system), ChatMessage(ChatRole.USER, parts))),
        ).text.trim()
        return observed.takeIf { it.isNotBlank() }?.take(6000) ?: language.text(
            "我没有读到清晰的屏幕内容。",
            "I couldn't read the screen clearly.",
        )
    }

    override suspend fun runPhoneTask(goal: String, skillName: String?, listener: AgentListener, stop: StopSignal): AgentEvent.PhoneTaskFinished =
        runPhoneTask(goal, skillName, listener, stop, language())

    private suspend fun runPhoneTask(
        goal: String,
        skillName: String?,
        listener: AgentListener,
        stop: StopSignal,
        language: AgentLanguage,
    ): AgentEvent.PhoneTaskFinished {
        val taskId = idFactory()
        fun finish(success: Boolean, summary: String, steps: Int) =
            AgentEvent.PhoneTaskFinished(taskId, success, summary, steps).also(listener::onEvent)

        val wanted = skillName?.trim()?.takeIf { it.isNotEmpty() }
        val skill = wanted?.let { quietly(null) { skills.get(it) } }
        if (skill?.channel == SkillChannel.CHAT) {
            val message = language.text(
                "${Prompts.CHAT_SKILL_PHONE_REFUSED}：${skill.name}",
                "This skill runs in chat: ${skill.name}",
            )
            return finish(false, message, 0)
        }
        if (skill != null) listener.onEvent(AgentEvent.SkillChosen(taskId, skill.name))
        listener.onEvent(AgentEvent.PhoneTaskStarted(taskId, goal, skill?.name))
        if (wanted != null && skill == null) {
            return finish(false, language.text("${Prompts.SKILL_NOT_FOUND}：$wanted", "Skill not found: $wanted"), 0)
        }

        val task = TaskRun(taskId, goal, skill, listener, stop, language)
        val outcome = try {
            task.references = references(skill)
            task.howto = howto(goal, skill)
            task.apps = appsSection()
            task.schemes = schemesSection()
            val flow = skill?.flow
            if (flow != null && flow.steps.isNotEmpty()) flows.run(task, flow)
            else loop.tryStandaloneScreenshot(task) ?: loop.run(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            val message = Prompts.operatorLlmFailed(e, language)
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        } catch (e: Exception) {
            // e.g. the accessibility service is not connected: device.observe() throws.
            val message = e.message?.takeIf { it.isNotBlank() } ?: language.text(Prompts.UNEXPECTED_ERROR, "An error occurred while running the task.")
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        }
        if (outcome.success && config.learnFromSuccess) quietly(Unit) { learn(task) }
        return finish(outcome.success, outcome.summary, task.steps)
    }

    private fun builtInSpecs(language: AgentLanguage, hasUserImage: Boolean = false): List<ToolSpec> {
        var specs = AgentTools.specs.map { spec ->
            if (spec.name != AgentTools.INSPECT_SCREEN) spec else spec.copy(
                description = language.text(
                    "只读查看当前手机屏幕（截图与无障碍文字），不点击、不操作、不保存截图。用户问‘这是什么’、‘屏幕上有什么’等指当前屏幕的问题时使用。用户已经随消息发来图片时不要使用。",
                    "Read the current phone screen using its screenshot and accessibility text. Read-only: do not tap, operate, or save a screenshot. Use when the user asks what something is or what is on the current screen. Do not use it when the user attached an image with the message.",
                ),
            )
        }
        // The attached picture already shows the scene, and screen reading fails when accessibility is off.
        if (hasUserImage) specs = specs.filter { it.name != AgentTools.INSPECT_SCREEN }
        if (!config.phoneTasks) specs = specs.filter { it.name != AgentTools.PHONE_TASK }
        if (skillStore == null) specs = specs.filter { it.name !in AgentTools.SKILL_TOOL_NAMES }
        return specs
    }

    /** Skill changes are confirmed through the same chat question channel as external tools. */
    private suspend fun confirmByAsk(taskId: String, prompt: String, language: AgentLanguage): Boolean {
        val options = listOf(language.text("确认", "Confirm"), language.text("取消", "Cancel"))
        val reply = users.ask(taskId, prompt, takeover = false, options = options)
        return reply.trim().lowercase() in CONFIRM_WORDS
    }

    /** External tools that do not clash with built-in names; a failing provider simply offers nothing. */
    private suspend fun externalSpecs(): List<ToolSpec> {
        val provider = externalTools ?: return emptyList()
        val builtIn = AgentTools.specs.map { it.name }.toSet()
        return try {
            provider.specs().filter { it.name !in builtIn }.distinctBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun systemPrompt(
        userMessage: String,
        language: AgentLanguage,
        hasExternalTools: Boolean = false,
        hasUserImage: Boolean = false,
    ): String = buildString {
        append(Prompts.persona(persona, language).trim()).append("\n\n").append(language.chatInstruction())
        if (hasExternalTools) append("\n").append(language.text(Prompts.EXTERNAL_TOOLS_HINT, "Tools containing __ come from external services. Prefer them when they can complete a task. Use their actual results and wait for required confirmation."))
        if (hasUserImage) append("\n").append(Prompts.userImageHint(language))
        append("\n\n").append(Prompts.nowSection(ReminderTimes.promptNow(clock()), language))
        val list = quietly(emptyList()) { skills.list() }
        if (config.phoneTasks) {
            append("\n\n").append(language.text(Prompts.SKILLS_HEADER, "## Device skills (the phone_task skill parameter)")).append('\n')
            val phoneSkills = list.filter { it.channel != SkillChannel.CHAT }
            if (phoneSkills.isEmpty()) append(language.text(Prompts.NO_SKILLS, "No skills available. Use the task goal directly."))
            else phoneSkills.forEach { skill ->
                append("- ").append(skill.name).append(": ").append(skill.description)
                if (skill.deeplinks.isNotEmpty()) {
                    append(" [").append(skill.deeplinks.entries.joinToString("；") { "${it.key}=${it.value}" }).append(']')
                }
                append('\n')
            }
            val platform = quietly("") { operatorNotes(language) }
            if (platform.isNotBlank()) append('\n').append(platform).append('\n')
            val apps = appsSection()
            if (apps.isNotBlank()) {
                append('\n').append(language.text(Prompts.APPS_HEADER, "## Apps installed on this phone")).append('\n')
                append(language.text(Prompts.APPS_HINT, "Use these packages when opening an app; do not guess packages from other devices.")).append('\n')
                append(apps).append('\n')
            }
            val links = schemesSection()
            if (links.isNotBlank()) {
                append('\n').append(language.text(Prompts.SCHEMES_HEADER, "## URI schemes declared by apps")).append('\n')
                append(language.text(Prompts.SCHEMES_HINT, Prompts.SCHEMES_HINT_EN)).append('\n')
                append(links).append('\n')
            }
        }
        val chatSkills = list.filter { it.channel == SkillChannel.CHAT }
        if (chatSkills.isNotEmpty()) {
            append("\n\n").append(language.text(Prompts.CHAT_SKILLS_HEADER, "## Chat workflows")).append('\n').append(language.text(Prompts.CHAT_SKILLS_HINT, "When a chat workflow matches, follow its instructions in chat. Use memory, reminders or external tools as needed."))
            var remaining = MAX_CHAT_SKILLS_CHARS
            for (skill in chatSkills) {
                append("\n\n### ").append(skill.name).append("：").append(skill.description)
                if (remaining <= 0) continue
                val instructions = skill.instructions.take(MAX_CHAT_SKILL_CHARS).take(remaining)
                if (instructions.isNotEmpty()) append('\n').append(instructions)
                remaining -= instructions.length
                // References share the workflow budget so many notes cannot inflate the prompt.
                for (ref in skill.memoryRefs) {
                    if (remaining <= 0) break
                    val note = quietly(null) { memory.read(ref) } ?: continue
                    val text = NoteText.forPrompt(note).take(remaining)
                    append("\n\n").append(text)
                    remaining -= text.length
                }
            }
        }
        val hits = quietly(emptyList()) { memory.search(userMessage, MEMORY_HITS) }
        if (hits.isNotEmpty()) {
            append("\n\n").append(language.text(Prompts.MEMORY_HEADER, "## Potentially relevant memories")).append('\n')
            hits.forEach { append("- ").append(it.title).append(" (").append(it.permalink).append("): ").append(it.snippet.replace('\n', ' ')).append('\n') }
        }
    }.trimEnd()

    private suspend fun remember(userMessage: String, reply: String?) = historyLock.withLock {
        history.addLast(ChatMessage.user(userMessage))
        if (!reply.isNullOrBlank()) history.addLast(ChatMessage.assistant(reply))
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        // Never start the window with an assistant message.
        while (history.firstOrNull()?.role == ChatRole.ASSISTANT) history.removeFirst()
    }

    /** Installed apps of this phone; cached so chat turns and steps do not query the package manager every time. */
    private suspend fun appsSection(): String {
        val now = clock()
        appsCache?.let { (at, text) -> if (now - at < APPS_TTL_MS) return text }
        return quietly("") { Prompts.appsSection(device.installedApps()) }.also { appsCache = now to it }
    }

    /** Deep links of installed apps, cached like [appsSection]. */
    private suspend fun schemesSection(): String {
        val now = clock()
        schemesCache?.let { (at, text) -> if (now - at < APPS_TTL_MS) return text }
        return quietly("") {
            Prompts.schemesSection(device.installedApps(), device.appSchemes())
        }.also { schemesCache = now to it }
    }

    private suspend fun references(skill: Skill?): String {
        if (skill == null) return ""
        return skill.memoryRefs.mapNotNull { ref -> quietly(null) { memory.read(ref) }?.let { NoteText.forPrompt(it) } }.joinToString("\n\n")
    }

    /** `howto` notes for the goal: the skill's own note first, then search hits of type howto. */
    private suspend fun howto(goal: String, skill: Skill?): String {
        val notes = LinkedHashMap<String, Note>()
        skill?.let { s -> quietly(null) { memory.read(howtoPermalink(s.name)) }?.let { notes[it.permalink] = it } }
        for (hit in quietly(emptyList()) { memory.search(goal, MEMORY_HITS) }) {
            if (notes.size >= MAX_HOWTO_NOTES) break
            if (hit.permalink in notes) continue
            quietly(null) { memory.read(hit.permalink) }?.takeIf { it.type == HOWTO_TYPE }?.let { notes[it.permalink] = it }
        }
        return notes.values.take(MAX_HOWTO_NOTES).joinToString("\n\n") { NoteText.forPrompt(it, maxChars = 800) }
    }

    /** Records the successful path as `- [path] a → b → c #skill` in `howto/<skill-or-app>`. */
    private suspend fun learn(task: TaskRun) {
        if (task.path.isEmpty()) return
        val key = task.skill?.name ?: task.launchedPackage ?: task.lastPackage ?: return
        val permalink = howtoPermalink(key)
        val observation = Observation(PATH_CATEGORY, task.path.joinToString(" → "), tags = listOf(key))
        val existing = memory.read(permalink)
        if (existing != null) {
            if (existing.observations.any { it.category == observation.category && it.content == observation.content }) return
            memory.appendObservation(permalink, observation)
        } else {
            val title = "$key 操作路径"
            memory.write(
                Note(
                    title = title,
                    permalink = permalink,
                    type = HOWTO_TYPE,
                    tags = listOf(HOWTO_TYPE),
                    body = NoteText.body(title, listOf(observation), emptyList()),
                    observations = listOf(observation),
                    updatedAtMillis = clock(),
                ),
            )
        }
        task.emit(AgentEvent.MemoryUpdated(task.taskId, permalink))
    }

    private suspend fun <T> quietly(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 8
        const val HISTORY_LIMIT = 20
        const val APPS_TTL_MS = 10 * 60 * 1000L
        const val MEMORY_HITS = 5
        const val HOWTO_TYPE = "howto"
        const val PATH_CATEGORY = "path"
        private const val MAX_HOWTO_NOTES = 3
        const val MAX_CHAT_SKILL_CHARS = 2000
        const val MAX_CHAT_SKILLS_CHARS = 8000

        private val CONFIRM_WORDS = setOf("确认", "是", "好", "可以", "confirm", "y", "yes")

        fun howtoPermalink(key: String) = "howto/$key"
    }
}
