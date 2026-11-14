package dev.xiaoman.app.session

import android.content.res.Resources
import dev.xiaoman.api.Action
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.SkillChange
import dev.xiaoman.app.R

/** Formats agent events for the chat and status surfaces using the current app language. */
internal class StepText(private val resources: Resources) {
    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    fun action(action: Action): String = when (action) {
        is Action.Tap -> text(R.string.agent_action_tap, where(action.target, action.nodeId))
        is Action.LongPress -> text(R.string.agent_action_long_press, where(action.target, action.nodeId))
        is Action.Swipe -> text(R.string.agent_action_swipe, where(action.target, null))
        is Action.TypeText -> text(R.string.agent_action_type, action.text, where(action.target, action.nodeId))
        is Action.Back -> text(R.string.agent_action_back)
        is Action.Home -> text(R.string.agent_action_home)
        is Action.LaunchApp -> text(R.string.agent_action_launch, action.packageName)
        is Action.OpenDeeplink -> text(R.string.agent_action_link, action.uri.substringBefore('?'))
        is Action.Wait -> text(R.string.agent_action_wait, action.millis)
        is Action.AskUser -> text(R.string.agent_action_ask, action.question)
        is Action.Finish -> text(R.string.agent_action_finish, action.summary)
    }

    private fun where(target: String?, nodeId: Int?): String = when {
        !target.isNullOrBlank() -> " $target"
        nodeId != null -> " #$nodeId"
        else -> ""
    }

    fun approval(prompt: String): String = text(R.string.agent_status_approval, prompt)

    /** Overlay and notification status, or null to keep the previous status. */
    fun status(event: AgentEvent): String? = when (event) {
        is AgentEvent.PhoneTaskStarted -> text(R.string.agent_status_start, event.goal)
        is AgentEvent.StepPlanned -> text(R.string.agent_status_step, event.stepIndex + 1, action(event.action))
        is AgentEvent.StepBlocked -> text(R.string.agent_status_blocked, event.stepIndex + 1)
        is AgentEvent.NeedsUser -> if (event.takeover) text(R.string.agent_status_takeover) else text(R.string.agent_status_answer, event.question)
        is AgentEvent.PhoneTaskFinished -> text(if (event.success) R.string.agent_status_done else R.string.agent_status_incomplete)
        else -> null
    }

    fun chatItem(event: AgentEvent, id: Long): ChatItem? = when (event) {
        is AgentEvent.Reply -> ChatItem.Assistant(id, event.text, System.currentTimeMillis())
        is AgentEvent.SkillChosen -> ChatItem.Progress(id, text(R.string.agent_log_skill, event.skillName))
        is AgentEvent.PhoneTaskStarted -> ChatItem.Progress(id, text(R.string.agent_log_task, event.goal))
        is AgentEvent.StepPlanned -> null
        is AgentEvent.StepExecuted -> {
            val step = event.stepIndex + 1
            val description = action(event.action)
            val failure = event.result.message?.takeIf { !event.result.ok }
            val line = if (failure.isNullOrBlank()) text(R.string.agent_log_step, step, description)
            else text(R.string.agent_log_step_failed, step, description, failure)
            ChatItem.Progress(id, line, event.result.ok)
        }
        is AgentEvent.StepBlocked -> ChatItem.Progress(
            id, text(R.string.agent_log_step_blocked, event.stepIndex + 1, action(event.action), event.decision.reason), false,
        )
        is AgentEvent.NeedsUser -> ChatItem.Question(id, event.question, event.takeover)
        is AgentEvent.MemoryUpdated -> ChatItem.Progress(id, text(R.string.agent_log_memory, event.permalink))
        is AgentEvent.SkillUpdated -> ChatItem.Progress(
            id,
            text(when (event.change) {
                SkillChange.CREATED -> R.string.agent_log_skill_created
                SkillChange.UPDATED -> R.string.agent_log_skill_updated
                SkillChange.DELETED -> R.string.agent_log_skill_deleted
            }, event.skillName),
            true,
        )
        is AgentEvent.ToolCalled -> ChatItem.Progress(id, event.summary, event.ok)
        is AgentEvent.PhoneTaskFinished -> ChatItem.Progress(
            id,
            resources.getQuantityString(
                if (event.success) R.plurals.agent_log_task_done else R.plurals.agent_log_task_failed,
                event.steps, event.steps, event.summary,
            ),
            event.success,
        )
        is AgentEvent.Failed -> ChatItem.Error(id, event.message)
    }
}
