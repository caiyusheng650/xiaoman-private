package dev.xiaoman.api

/**
 * A scenario skill written by users: a folder with `SKILL.md` and an optional `flow.yaml`.
 *
 * `SKILL.md` frontmatter:
 * ```
 * ---
 * name: taxi-home                 # required, [a-z0-9-], unique
 * description: 打车回家            # required, one line; shown to the chat model to pick skills
 * apps: [com.sdu.didi.psnger]     # package names the skill may operate
 * channel: deeplink-then-gui      # api | cli | deeplink | gui | deeplink-then-gui | mixed | chat
 * confirm_before: [确认呼叫, 支付]  # words that force a user confirmation before tapping/typing on them
 * memory: [地址/家, 偏好/车型]       # notes to load into the operator or chat prompt
 * deeplinks:                      # optional named deep links; {placeholders} filled by the operator
 *   search: "amapuri://route/plan/?dname={destination}"
 * ---
 * Markdown instructions for the operator or chat model.
 * ```
 *
 * Channel CHAT runs the Markdown body in the conversation; other channels serve phone tasks.
 * Chat workflows forbid apps, deeplinks, confirm_before and flow.yaml; memory is allowed.
 */
data class Skill(
    val name: String,
    val description: String,
    val apps: List<String> = emptyList(),
    val channel: SkillChannel = SkillChannel.GUI,
    val confirmBefore: List<String> = emptyList(),
    val memoryRefs: List<String> = emptyList(),
    val deeplinks: Map<String, String> = emptyMap(),
    /** Markdown body of SKILL.md after the frontmatter. */
    val instructions: String,
    val flow: Flow? = null,
    /** Where the skill was loaded from (folder path or URI), for display and reload. */
    val source: String,
)

enum class SkillChannel(val wire: String) {
    API("api"), CLI("cli"), DEEPLINK("deeplink"), GUI("gui"), DEEPLINK_THEN_GUI("deeplink-then-gui"), MIXED("mixed"), CHAT("chat");

    companion object {
        fun fromWire(value: String): SkillChannel? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/**
 * Optional `flow.yaml`: fixed steps run before or between free operator steps.
 * ```
 * steps:
 *   - launch: com.autonavi.minimap
 *   - deeplink: search            # a key of SKILL.md deeplinks, or a literal URI
 *   - ai: 选择快车并读出预估价       # a sub-goal handed to the operator loop
 *   - confirm: 确认呼叫快车？        # explicit user confirmation
 *   - tap: 确认呼叫                 # tap the element best matching this description
 *   - type: { target: 目的地, text: "{destination}" }
 *   - wait: 1500
 *   - assert: 行程已开始             # operator checks the screen shows this, else the flow fails
 * ```
 */
data class Flow(val steps: List<FlowStep>)

sealed class FlowStep {
    data class Launch(val packageName: String) : FlowStep()
    data class Deeplink(val keyOrUri: String) : FlowStep()
    data class Ai(val goal: String) : FlowStep()
    data class Confirm(val question: String) : FlowStep()
    data class Tap(val target: String) : FlowStep()
    data class Type(val target: String, val text: String) : FlowStep()
    data class Wait(val millis: Long) : FlowStep()
    data class Assert(val expectation: String) : FlowStep()
}

data class SkillProblem(val source: String, val message: String, val line: Int? = null)

/** Result of loading skills: problems never abort loading of the other skills. */
data class SkillLoadResult(val skills: List<Skill>, val problems: List<SkillProblem>)

interface SkillRepository {
    suspend fun reload(): SkillLoadResult

    suspend fun list(): List<Skill>

    suspend fun get(name: String): Skill?
}

/** What happened to a skill the agent wrote or deleted. */
enum class SkillChange { CREATED, UPDATED, DELETED }

/**
 * A model-authored SKILL.md checked against the same rules as the folder loader, without touching
 * disk. [skill] is null together with non-empty [problems] when the draft is not committable.
 * [created] is false when a user skill of the same name already exists and the commit would replace it.
 */
data class SkillDraft(
    val skillMd: String,
    val flowYaml: String?,
    val skill: Skill?,
    val problems: List<SkillProblem>,
    val created: Boolean,
)

/** Outcome of persisting a [SkillDraft]: [skill] is null with [problems] when the write failed. */
data class SkillWriteResult(val skill: Skill?, val problems: List<SkillProblem>, val created: Boolean)

/**
 * A [SkillRepository] that can also persist skills authored in conversation. Implementations write
 * to one writable user folder and must never modify bundled roots; validation reuses the loader
 * rules so an invalid draft can never reach the folder.
 */
interface SkillStore : SkillRepository {
    /** Validates SKILL.md content (and optional flow.yaml) without writing anything. */
    suspend fun draft(skillMd: String, flowYaml: String? = null): SkillDraft

    /** Writes a previously validated draft to the user skill folder and reloads the repository. */
    suspend fun commit(draft: SkillDraft): SkillWriteResult

    /** Deletes a skill from the user skill folder; false when absent or not user-writable. */
    suspend fun deleteUserSkill(name: String): Boolean
}
