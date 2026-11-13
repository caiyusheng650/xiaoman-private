package dev.xiaoman.skills

import dev.xiaoman.api.FlowStep
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectorySkillRepositoryTest {
    private val tmp: File = Files.createTempDirectory("skills-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun skill(root: File, folder: String, name: String = folder, description: String = "描述", flow: String? = null) {
        val dir = File(root, folder).apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $name\ndescription: $description\n---\n说明 $folder\n")
        if (flow != null) File(dir, "flow.yaml").writeText(flow)
    }

    private fun TestScope.repo(vararg roots: File) = DirectorySkillRepository(roots.toList(), io = StandardTestDispatcher(testScheduler))

    @Test
    fun loadsExamplesWithoutProblems() = runTest {
        val examples = File(
            System.getProperty("xiaoman.skills.examples")
                ?: File(System.getProperty("user.dir")).parentFile.resolve("skills/examples").path,
        )
        assertTrue(examples.isDirectory, "missing $examples")
        val result = DirectorySkillRepository(listOf(examples)).reload()
        assertEquals(emptyList(), result.problems)
        assertEquals(
            listOf(
                "ai-news-brief", "daily-quiz", "health-alarms", "meituan-takeout-order", "open-settings-wifi", "set-alarm",
                "takeout-reorder", "taobao-compare", "taxi-home", "today-plan", "train-ticket-order",
            ),
            result.skills.map { it.name },
        )
        val taxi = result.skills.single { it.name == "taxi-home" }
        assertEquals(listOf("确认呼叫"), taxi.confirmBefore)
        assertEquals(FlowStep.Deeplink("route"), taxi.flow?.steps?.first())
    }

    @Test
    fun laterRootsOverrideEarlierOnes() = runTest {
        val bundled = File(tmp, "bundled")
        val user = File(tmp, "user")
        skill(bundled, "taxi-home", description = "内置")
        skill(bundled, "open-settings-wifi")
        skill(user, "taxi-home", description = "用户")
        val repo = repo(bundled, user)
        assertEquals("用户", repo.get("taxi-home")?.description)
        assertEquals(listOf("open-settings-wifi", "taxi-home"), repo.list().map { it.name })
        assertTrue(repo.get("taxi-home")!!.source.startsWith(user.path))
    }

    @Test
    fun brokenUserSkillKeepsBundledOne() = runTest {
        val bundled = File(tmp, "bundled")
        val user = File(tmp, "user")
        skill(bundled, "taxi-home", description = "内置")
        skill(user, "taxi-home", flow = "steps:\n  - fly: away\n")
        val result = repo(bundled, user).reload()
        assertEquals("内置", result.skills.single().description)
        assertEquals(1, result.problems.size)
    }

    @Test
    fun oneBadSkillDoesNotBlockOthers() = runTest {
        skill(tmp, "good")
        skill(tmp, "bad-channel")
        File(tmp, "bad-channel/SKILL.md").writeText("---\nname: bad-channel\ndescription: d\nchannel: nope\n---\n")
        File(tmp, "no-skill-md").mkdirs()
        File(tmp, ".hidden").mkdirs()
        File(tmp, "README.txt").writeText("not a skill")
        val result = repo(tmp).reload()
        assertEquals(listOf("good"), result.skills.map { it.name })
        assertEquals(2, result.problems.size, result.problems.toString())
        assertTrue(result.problems.any { "unknown channel" in it.message && it.line == 4 })
        assertTrue(result.problems.any { it.message == "missing SKILL.md" })
    }

    @Test
    fun duplicateNamesInOneRoot() = runTest {
        skill(tmp, "taxi-home")
        skill(tmp, "taxi-copy", name = "taxi-home", description = "副本")
        val result = repo(tmp).reload()
        assertEquals("描述", result.skills.single().description)
        assertTrue(result.problems.any { "does not match folder name" in it.message })
        assertTrue(result.problems.any { "duplicate skill name 'taxi-home'" in it.message && it.source.endsWith("taxi-copy") })
    }

    @Test
    fun missingRootIsSkipped() = runTest {
        val result = repo(File(tmp, "nope")).reload()
        assertEquals(emptyList(), result.skills)
        assertEquals(emptyList(), result.problems)
    }

    @Test
    fun loadsLazilyAndReloadPicksUpChanges() = runTest {
        skill(tmp, "a")
        val repo = repo(tmp)
        val lists = (1..5).map { async { repo.list() } }.awaitAll()
        assertTrue(lists.all { it.map { s -> s.name } == listOf("a") })
        skill(tmp, "b")
        assertNull(repo.get("b"), "no rescan before reload")
        repo.reload()
        assertNotNull(repo.get("b"))
    }

    private val coffeeSkill = "---\nname: coffee-order\ndescription: 早上点咖啡\nchannel: chat\n---\n# 早上点咖啡\n按步骤来。\n"

    @Test
    fun draftAndCommitWritesAUserSkillAndReloads() = runTest {
        val user = File(tmp, "user")
        val repo = repo(user)
        val draft = repo.draft(coffeeSkill)
        assertTrue(draft.problems.isEmpty(), draft.problems.toString())
        assertTrue(draft.created)
        val result = repo.commit(draft)
        assertTrue(result.problems.isEmpty())
        assertEquals("coffee-order", result.skill?.name)
        assertTrue(File(user, "coffee-order/SKILL.md").isFile)
        assertEquals("早上点咖啡", repo.get("coffee-order")?.description)
    }

    @Test
    fun invalidDraftNeverTouchesDiskAndCommitAlsoRefuses() = runTest {
        val user = File(tmp, "user")
        val repo = repo(user)
        val draft = repo.draft("---\nname: Bad_Name\ndescription: d\n---\n")
        assertNull(draft.skill)
        assertTrue(draft.problems.isNotEmpty())
        assertEquals(emptyList(), user.listFiles()?.toList().orEmpty())
        assertNull(repo.commit(draft).skill)
    }

    @Test
    fun draftRejectsNamesShadowingBundledSkills() = runTest {
        val bundled = File(tmp, "bundled")
        val user = File(tmp, "user")
        skill(bundled, "taxi-home", description = "内置")
        val repo = repo(bundled, user)
        repo.reload()
        val draft = repo.draft("---\nname: taxi-home\ndescription: 想顶替内置\n---\n正文\n")
        assertNull(draft.skill)
        assertTrue(draft.problems.any { "内置技能冲突" in it.message })
        assertFalse(File(user, "taxi-home").exists())
    }

    @Test
    fun commitTwiceUpdatesTheSameUserSkill() = runTest {
        val user = File(tmp, "user")
        val repo = repo(user)
        repo.commit(repo.draft(coffeeSkill))
        val second = repo.draft("---\nname: coffee-order\ndescription: 第二版\nchannel: chat\n---\n新正文\n")
        assertFalse(second.created)
        val result = repo.commit(second)
        assertFalse(result.created)
        assertEquals("第二版", repo.get("coffee-order")?.description)
    }

    @Test
    fun draftValidatesFlowYamlAndCommitWritesIt() = runTest {
        val user = File(tmp, "user")
        val repo = repo(user)
        val md = "---\nname: launch-settings\ndescription: 打开设置\napps: [com.android.settings]\n---\n正文\n"
        assertTrue(repo.draft(md, "steps:\n  - fly: away\n").problems.isNotEmpty())
        val good = repo.draft(md, "steps:\n  - launch: com.android.settings\n")
        assertTrue(good.problems.isEmpty(), good.problems.toString())
        repo.commit(good)
        assertTrue(File(user, "launch-settings/flow.yaml").isFile)
    }

    @Test
    fun deleteOnlyRemovesUserSkills() = runTest {
        val bundled = File(tmp, "bundled")
        val user = File(tmp, "user")
        skill(bundled, "taxi-home", description = "内置")
        skill(user, "coffee-order")
        val repo = repo(bundled, user)
        repo.reload()
        assertFalse(repo.deleteUserSkill("taxi-home"))
        assertNotNull(repo.get("taxi-home"))
        assertTrue(repo.deleteUserSkill("coffee-order"))
        assertNull(repo.get("coffee-order"))
        assertFalse(File(user, "coffee-order").exists())
        assertFalse(repo.deleteUserSkill("missing"))
    }
}
