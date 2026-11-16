package dev.xiaoman.app

import android.content.Context
import android.util.Log
import dev.xiaoman.agent.AgentLanguage
import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.Agent
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ChatHistoryStore
import dev.xiaoman.app.session.SessionTexts
import dev.xiaoman.app.settings.AppearanceSettings
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.PetSettings
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.app.trace.RecordingDevice
import dev.xiaoman.app.trace.TraceWriter
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.AppLinks
import dev.xiaoman.hands.MutableStopSignal
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.llm.LlmClients
import dev.xiaoman.app.settings.McpSettingsStore
import dev.xiaoman.mcp.McpCatalog
import dev.xiaoman.mcp.McpConfig
import dev.xiaoman.mcp.McpPlatform
import dev.xiaoman.mcp.McpSelection
import dev.xiaoman.mcp.McpToolProvider
import dev.xiaoman.mcp.ServerStatus
import dev.xiaoman.mcp.UserChannelApprovals
import dev.xiaoman.memory.FileMemoryStore
import dev.xiaoman.sentinel.PolicySentinel
import dev.xiaoman.skills.DirectorySkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [XiaomanApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.xiaoman.app/files`):
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`, optional `mcp.json` (MCP servers,
 * HTTP only; keys are written in the file because Android has no environment); bundled skills are
 * extracted to `<files>/bundled-skills/`.
 */
class AppGraph(context: Context, private val llm: LlmClientFactory = LlmClients.default) {
    private val app: Context = context.applicationContext

    /** Process-wide scope; the session runs agent work here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val externalRoot: File = app.getExternalFilesDir(null) ?: File(app.filesDir, "external")
    val memoryDir = File(externalRoot, "memory")
    val skillsDir = File(externalRoot, "skills")
    val tracesDir = File(externalRoot, "traces")
    val bundledSkillsDir = File(app.filesDir, "bundled-skills")
    val mcpFile = File(externalRoot, "mcp.json")

    val appearance = AppearanceSettings(app)
    val petSettings = PetSettings(app)
    val settings: SettingsStore by lazy { SettingsStore.create(app) }

    val memory by lazy { FileMemoryStore(memoryDir) }

    val skills by lazy { DirectorySkillRepository(listOf(bundledSkillsDir, skillsDir)) }

    val notifier by lazy { Notifier(app) { appearance.localizedContext(app) } }

    val reminderScheduler by lazy { AlarmReminderScheduler(app) }

    /** Services ticked on the 服务 screen; keys live in the encrypted settings file. */
    val mcpSettings: McpSettingsStore by lazy { McpSettingsStore.create(app, settings.secret) }

    private var cachedMcp: Pair<McpSelection, McpToolProvider?>? = null

    /** MCP config of [selection] plus the HTTP servers of the optional `mcp.json` (stdio needs a desktop). */
    fun mcpConfig(selection: McpSelection): McpConfig {
        val ticked = McpCatalog.builtIn.toConfig(selection, McpPlatform.ANDROID)
        val file = McpConfig.load(mcpFile, emptyMap())
        val fileServers = file.servers.filter { it.enabled && it.url != null && ticked.servers.none { t -> t.name == it.name } }
        (ticked.warnings + file.warnings).forEach { Log.w(TAG, "mcp: $it") }
        return McpConfig(ticked.servers + fileServers, ticked.warnings + file.warnings)
    }

    /** The provider for [selection], rebuilt (and the old one closed) when the ticked services change. */
    @Synchronized
    private fun mcpFor(selection: McpSelection): McpToolProvider? {
        cachedMcp?.let { (s, provider) -> if (s == selection) return provider }
        cachedMcp?.second?.close()
        val config = mcpConfig(selection)
        val provider = if (config.servers.isEmpty()) null else McpToolProvider(config, UserChannelApprovals(session))
        cachedMcp = selection to provider
        return provider
    }

    /** Connects to the services of [selection] once, for the 测试连接 button; never kept. */
    suspend fun testMcp(selection: McpSelection): List<ServerStatus> {
        val config = mcpConfig(selection)
        if (config.servers.isEmpty()) return emptyList()
        return McpToolProvider(config, { false }).use { it.status(refresh = true) }
    }

    val runIds = RunIds()
    val uiVisible = MutableStateFlow(false)
    val assistantPanelVisible = MutableStateFlow(false)

    val traces by lazy {
        TraceWriter(
            root = tracesDir,
            secrets = { settings.current.secrets },
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) },
        )
    }

    val accessibilityDevice by lazy {
        AccessibilityDevice(
            { XiaomanAccessibilityService.instance.value },
            app,
            resources = { appearance.localizedContext(app).resources },
        )
    }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    val sentinel by lazy { PolicySentinel(approvals = session, audit = traces, selfPackage = app.packageName) }

    val session: AgentSession by lazy {
        AgentSession(
            scope = scope,
            agent = ::agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            resources = { appearance.localizedContext(app).resources },
            onTaskEnd = { sentinel.endTask(it) },
            texts = {
                val localized = appearance.localizedContext(app)
                SessionTexts(
                    notConfigured = localized.getString(R.string.session_not_configured),
                    busy = localized.getString(R.string.session_busy),
                    stopped = localized.getString(R.string.session_stopped),
                    interrupted = localized.getString(R.string.session_interrupted),
                    approvedOnce = { localized.getString(R.string.approval_resolved_allowed, it) },
                    approvedTask = { localized.getString(R.string.approval_resolved_task, it) },
                    denied = { localized.getString(R.string.approval_resolved_denied, it) },
                    imageDefaultPrompt = localized.getString(R.string.chat_image_default_prompt),
                    imageReadFailed = localized.getString(R.string.chat_image_read_failed),
                )
            },
            ready = { startup.await() },
            historyStore = ChatHistoryStore(File(app.noBackupFilesDir, "chat-history.json")),
            chatImagesDir = File(app.noBackupFilesDir, "chat-images"),
            onHistoryCleared = ::resetAgent,
            onResult = { text ->
                if (!uiVisible.value && !assistantPanelVisible.value) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (!uiVisible.value && !assistantPanelVisible.value) notifier.showResult(text)
                    }
                }
            },
        )
    }

    /** Bundled skills extracted and skills loaded once. */
    lateinit var startup: Deferred<Unit>
        private set

    private var cachedAgent: Pair<Pair<AppSettings, McpSelection>, Agent>? = null

    @Synchronized
    private fun resetAgent() {
        cachedAgent = null
    }

    /** The agent for the current settings, rebuilt when they change; null until both models are configured. */
    @Synchronized
    fun agent(): Agent? {
        val current = settings.current
        if (!current.configured) return null
        val key = current to mcpSettings.current
        cachedAgent?.let { (k, agent) -> if (k == key) return agent }
        val agent = XiaomanAgent(
            chat = llm.create(current.chat.toModelConfig(ModelRole.CHAT)),
            operator = llm.create(current.operator.toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig().copy(systemScreenshotUri = AppLinks.SYSTEM_SCREENSHOT_URI),
            operatorNotes = { accessibilityDevice.operatorNotes(english = it == AgentLanguage.ENGLISH) },
            idFactory = runIds::next,
            externalTools = mcpFor(key.second),
            skillStore = skills,
            language = {
                val locale = appearance.localizedContext(app).resources.configuration.locales[0]
                if (locale.language == "en") AgentLanguage.ENGLISH else AgentLanguage.CHINESE
            },
        )
        cachedAgent = key to agent
        return agent
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        scope.launch(Dispatchers.Main.immediate) {
            appearance.language.collect { notifier.createChannels() }
        }
        val conversationVisible = combine(uiVisible, assistantPanelVisible) { app, panel -> app || panel }
        scope.launch(Dispatchers.Main.immediate) {
            conversationVisible.collect { if (it) notifier.dismissResult() }
        }
        OverlayController(session, accessibilityDevice, conversationVisible, appearance, petSettings).start(scope)
        scope.launch(Dispatchers.Main.immediate) {
            session.busy.collect { busy -> if (busy) AgentService.start(app) }
        }
        scope.launch(Dispatchers.Main.immediate) { session.approval.collect(notifier::showApproval) }
        scope.launch(Dispatchers.Main.immediate) {
            combine(session.question, uiVisible, assistantPanelVisible) { question, app, panel ->
                question.takeUnless { app || panel }
            }
                .collect(notifier::showQuestion)
        }
    }

    private suspend fun prepareSkills() {
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = "${BuildConfig.VERSION_CODE}-${info.lastUpdateTime}"
            if (BundledSkills.extract(app.assets, bundledSkillsDir, version)) Log.i(TAG, "skills: bundled skills extracted ($version)")
            skillsDir.mkdirs()
            memoryDir.mkdirs()
        } catch (e: IOException) {
            Log.w(TAG, "skills: cannot extract bundled skills: ${e.message}")
        }
        val result = skills.reload()
        logSkillProblems(result.skills.size, result.problems)
    }

    fun logSkillProblems(count: Int, problems: List<dev.xiaoman.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "Xiaoman"
    }
}
