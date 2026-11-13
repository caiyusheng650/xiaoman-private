package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.SkillChange
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillWriteTest {
    private val skillMd = """
        ---
        name: coffee-order
        description: 在瑞幸下单一杯咖啡
        apps: [com.luckin.coffee]
        ---
        # 瑞幸点咖啡
        打开 App 后选择自提门店，付款前停下来等用户确认。
    """.trimIndent()

    private fun writeArgs(md: String = skillMd, flow: String? = null) = buildJsonObject {
        put("skill_md", md)
        if (flow != null) put("flow_yaml", flow)
    }.toString()

    @Test
    fun skillToolsAreOnlyOfferedWithAStore() = runTest {
        val without = Harness()
        without.chat.reply("好")
        without.agent.handle("你好", without.events, without.stop)
        assertFalse(without.chat.requests[0].tools.any { it.name in AgentTools.SKILL_TOOL_NAMES })

        val with = Harness(skillStore = FakeSkillStore())
        with.chat.reply("好")
        with.agent.handle("你好", with.events, with.stop)
        val names = with.chat.requests[0].tools.map { it.name }
        assertTrue(AgentTools.SKILL_WRITE in names && AgentTools.SKILL_DELETE in names)
    }

    @Test
    fun writeValidatesConfirmsCommitsAndEmits() = runTest {
        val store = FakeSkillStore()
        val h = Harness(skillStore = store, users = FakeUsers("确认"))
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_WRITE, writeArgs())).reply("已经帮你存成技能了。")

        h.agent.handle("把这个点咖啡流程存成技能", h.events, h.stop)

        assertEquals(1, store.commits.size)
        val event = h.events.of<AgentEvent.SkillUpdated>().single()
        assertEquals("coffee-order", event.skillName)
        assertEquals(SkillChange.CREATED, event.change)
        assertTrue(h.users.asked.single().first.contains("coffee-order"))
        val toolMsg = h.chat.requests[1].messages.last()
        assertTrue("\"ok\":true" in toolMsg.text && "\"created\":true" in toolMsg.text)
    }

    @Test
    fun invalidDraftReturnsProblemsWithoutAskingOrWriting() = runTest {
        val store = FakeSkillStore().apply {
            draftProblems = listOf(SkillProblem("draft/SKILL.md", "name 'Bad Name' must match [a-z0-9-]+"))
        }
        val h = Harness(skillStore = store, users = FakeUsers("确认"))
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_WRITE, writeArgs())).reply("我修一下。")

        h.agent.handle("存技能", h.events, h.stop)

        assertTrue(store.commits.isEmpty())
        assertTrue(h.users.asked.isEmpty())
        assertTrue(h.events.of<AgentEvent.SkillUpdated>().isEmpty())
        assertTrue("校验未通过" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun userDeclineStopsTheWrite() = runTest {
        val store = FakeSkillStore()
        val h = Harness(skillStore = store, users = FakeUsers("先不要"))
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_WRITE, writeArgs())).reply("好的，先不存。")

        h.agent.handle("存技能", h.events, h.stop)

        assertTrue(store.commits.isEmpty())
        assertTrue("没有确认" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun rewritingAnExistingUserSkillIsAnUpdate() = runTest {
        val store = FakeSkillStore(listOf(skill("coffee-order", "旧版描述")))
        val h = Harness(skillStore = store, users = FakeUsers("确认"))
        h.chat.tools(ToolCall("c1", AgentTools.SKILL_WRITE, writeArgs())).reply("更新好了。")

        h.agent.handle("把点咖啡技能更新一下", h.events, h.stop)

        val event = h.events.of<AgentEvent.SkillUpdated>().single()
        assertEquals(SkillChange.UPDATED, event.change)
        assertTrue("\"created\":false" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun deleteConfirmsAndEmits() = runTest {
        val store = FakeSkillStore(listOf(skill("coffee-order", "点咖啡")))
        val h = Harness(
            skills = FakeSkills(skill("coffee-order", "点咖啡")),
            skillStore = store,
            users = FakeUsers("确认"),
        )
        h.chat.tools(ToolCall("d1", AgentTools.SKILL_DELETE, """{"name":"coffee-order"}""")).reply("删掉了。")

        h.agent.handle("把点咖啡技能删掉", h.events, h.stop)

        assertEquals(listOf("coffee-order"), store.deletes)
        val event = h.events.of<AgentEvent.SkillUpdated>().single()
        assertEquals(SkillChange.DELETED, event.change)
        assertTrue("\"deleted\":true" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun deleteOfANonWritableSkillIsRejected() = runTest {
        // The repository sees the skill, but the writable store does not own it: it is bundled.
        val h = Harness(
            skills = FakeSkills(skill("taxi-home", "内置打车技能")),
            skillStore = FakeSkillStore(),
            users = FakeUsers("确认"),
        )
        h.chat.tools(ToolCall("d1", AgentTools.SKILL_DELETE, """{"name":"taxi-home"}""")).reply("删不掉。")

        h.agent.handle("删除打车技能", h.events, h.stop)

        assertTrue("内置技能" in h.chat.requests[1].messages.last().text)
        assertTrue(h.events.of<AgentEvent.SkillUpdated>().isEmpty())
    }
}
