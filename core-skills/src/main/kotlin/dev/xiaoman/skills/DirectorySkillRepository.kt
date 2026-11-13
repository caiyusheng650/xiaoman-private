package dev.xiaoman.skills

import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillChannel
import dev.xiaoman.api.SkillDraft
import dev.xiaoman.api.SkillLoadResult
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.SkillStore
import dev.xiaoman.api.SkillWriteResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Loads skills from folders `<root>/<name>/SKILL.md` (+ optional `flow.yaml`).
 *
 * Later [roots] override earlier ones by skill name, so pass bundled skills first and user skills
 * last. Missing roots are skipped silently (the user folder may not exist yet). A broken skill is
 * reported in [SkillLoadResult.problems] and never stops the others from loading; when a user skill
 * is broken, a bundled skill of the same name stays in place.
 *
 * As a [SkillStore], writes only ever touch [writableRoot] (defaults to the last root): model
 * authored skills are validated with the same parser as the loader before anything is written, and
 * names colliding with bundled skills are rejected outright.
 *
 * [list] and [get] load lazily on first use; [reload] rescans. Safe to call from any thread.
 */
class DirectorySkillRepository(
    private val roots: List<File>,
    private val writableRoot: File? = roots.lastOrNull(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SkillStore {
    private val mutex = Mutex()

    @Volatile
    private var state: State? = null

    override suspend fun reload(): SkillLoadResult = mutex.withLock { load() }.result

    override suspend fun list(): List<Skill> = loaded().result.skills

    override suspend fun get(name: String): Skill? = loaded().byName[name]

    override suspend fun draft(skillMd: String, flowYaml: String?): SkillDraft = mutex.withLock {
        withContext(io) {
            if (state == null) load()
            draftLocked(skillMd, flowYaml)
        }
    }

    override suspend fun commit(draft: SkillDraft): SkillWriteResult = mutex.withLock {
        withContext(io) {
            if (state == null) load()
            commitLocked(draft)
        }
    }

    override suspend fun deleteUserSkill(name: String): Boolean = mutex.withLock {
        withContext(io) {
            if (state == null) load()
            deleteLocked(name)
        }
    }

    private suspend fun loaded(): State = state ?: mutex.withLock { state ?: load() }

    /** Must hold [mutex]. */
    private suspend fun load(): State = withContext(io) {
        val merged = LinkedHashMap<String, Skill>()
        val problems = mutableListOf<SkillProblem>()
        for (root in roots) {
            val (skills, rootProblems) = loadRoot(root)
            problems += rootProblems
            skills.forEach { merged[it.name] = it }
        }
        val skills = merged.values.sortedBy { it.name }
        State(SkillLoadResult(skills, problems), skills.associateBy { it.name })
    }.also { state = it }

    /** Must hold [mutex]. Pure validation, no disk writes. */
    private fun draftLocked(skillMd: String, flowYaml: String?): SkillDraft {
        val root = writableRoot
        val baseSource = root?.let { File(it, "draft").path } ?: "draft"
        val (parsed, parseProblems) = SkillParser.parse(skillMd, "$baseSource/SKILL.md")
        if (parsed == null) return SkillDraft(skillMd, flowYaml, null, parseProblems, true)

        val problems = parseProblems.toMutableList()
        val folder = root?.let { File(it, parsed.name) }
        val existing = state?.byName?.get(parsed.name)
        if (existing != null && !isUserSkill(existing)) {
            problems += SkillProblem(
                "$folder/SKILL.md",
                "技能名 '${parsed.name}' 与内置技能冲突，请换一个 name",
            )
        }
        if (flowYaml != null) {
            if (parsed.channel == SkillChannel.CHAT) {
                problems += SkillProblem("$folder/flow.yaml", "聊天工作流不操作手机，不能设置 flow.yaml", 1)
            } else {
                problems += FlowParser.parse(flowYaml, "$folder/flow.yaml", parsed.deeplinks, parsed.apps).second
            }
        }
        return if (problems.isNotEmpty()) {
            SkillDraft(skillMd, flowYaml, null, problems, true)
        } else {
            SkillDraft(skillMd, flowYaml, parsed, emptyList(), created = existing == null)
        }
    }

    /** Must hold [mutex]. */
    private suspend fun commitLocked(draft: SkillDraft): SkillWriteResult {
        val root = writableRoot
            ?: return SkillWriteResult(null, listOf(SkillProblem("skills", "没有可写的技能目录")), draft.created)
        // Re-validate under the lock so a stale draft cannot bypass the rules.
        val rechecked = draftLocked(draft.skillMd, draft.flowYaml)
        val parsed = rechecked.skill
            ?: return SkillWriteResult(null, rechecked.problems, rechecked.created)
        return try {
            writeSkillFiles(root, parsed.name, draft.skillMd, draft.flowYaml)
            val fresh = load()
            val stored = fresh.byName[parsed.name]
            if (stored == null) {
                val folderProblems = fresh.result.problems.filter { it.source.startsWith(File(root, parsed.name).path) }
                SkillWriteResult(null, folderProblems, rechecked.created)
            } else {
                SkillWriteResult(stored, emptyList(), rechecked.created)
            }
        } catch (e: IOException) {
            SkillWriteResult(null, listOf(SkillProblem(File(root, parsed.name).path, "写入失败：${e.message}")), rechecked.created)
        }
    }

    /** Must hold [mutex]. */
    private suspend fun deleteLocked(name: String): Boolean {
        val root = writableRoot ?: return false
        val current = state ?: return false
        val existing = current.byName[name] ?: return false
        if (!isUserSkill(existing)) return false
        val folder = File(root, name)
        if (!folder.isDirectory) return false
        val deleted = folder.deleteRecursively()
        if (deleted) load()
        return deleted
    }

    private fun isUserSkill(skill: Skill): Boolean {
        val root = writableRoot ?: return false
        return runCatching {
            File(skill.source).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
        }.getOrDefault(false)
    }

    private fun writeSkillFiles(root: File, name: String, skillMd: String, flowYaml: String?) {
        val folder = File(root, name)
        if (!folder.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw IOException("技能名越出技能目录：$name")
        }
        if (!folder.exists() && !folder.mkdirs()) throw IOException("无法创建目录 $folder")
        File(folder, "SKILL.md").writeText(skillMd, Charsets.UTF_8)
        val flowFile = File(folder, "flow.yaml")
        if (flowYaml != null) {
            flowFile.writeText(flowYaml, Charsets.UTF_8)
        } else if (flowFile.isFile) {
            flowFile.delete()
        }
    }

    private class State(val result: SkillLoadResult, val byName: Map<String, Skill>)

    companion object {
        /** Loads one root without overrides; duplicate skill names inside it are problems. */
        fun loadRoot(root: File): SkillLoadResult {
            if (!root.exists()) return SkillLoadResult(emptyList(), emptyList())
            val folders = root.listFiles()
                ?: return SkillLoadResult(emptyList(), listOf(SkillProblem(root.path, "not a readable directory")))
            val parsed = mutableListOf<Pair<File, Skill>>()
            val problems = mutableListOf<SkillProblem>()
            for (folder in folders.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }) {
                val skillFile = File(folder, "SKILL.md")
                if (!skillFile.isFile) {
                    problems += SkillProblem(folder.path, "missing SKILL.md")
                    continue
                }
                val flowFile = File(folder, "flow.yaml")
                val (skill, skillProblems) = try {
                    SkillParser.parseFolder(
                        name = folder.name,
                        skillMd = skillFile.readText(),
                        flowYaml = if (flowFile.isFile) flowFile.readText() else null,
                        source = folder.path,
                    )
                } catch (e: IOException) {
                    null to listOf(SkillProblem(folder.path, "cannot read: ${e.message}"))
                }
                problems += skillProblems
                if (skill != null) parsed += folder to skill
            }
            val skills = parsed.groupBy({ it.second.name }) { it }.map { (name, candidates) ->
                // Prefer the folder whose name matches; report the rest.
                val keep = candidates.firstOrNull { it.first.name == name } ?: candidates.first()
                for ((folder, _) in candidates) if (folder != keep.first) {
                    problems += SkillProblem(folder.path, "duplicate skill name '$name' (also in ${keep.first.path}); ignored")
                }
                keep.second
            }
            return SkillLoadResult(skills, problems)
        }
    }
}
