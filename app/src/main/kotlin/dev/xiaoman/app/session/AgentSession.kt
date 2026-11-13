package dev.xiaoman.app.session

import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.UserChannel
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Texts the session adds to the chat itself; resolved from resources by the app. */
data class SessionTexts(
    val notConfigured: String = "还没有配置模型。",
    val busy: String = "已有任务在运行。",
    val stopped: String = "已停止",
    val interrupted: String = "上次对话中的任务已中断，请重新发起。",
    val approvedOnce: (String) -> String = { "已允许：$it" },
    val approvedTask: (String) -> String = { "本次任务都允许：$it" },
    val denied: (String) -> String = { "已拒绝：$it" },
)

class PendingQuestion(
    val id: Long,
    val taskId: String,
    val question: String,
    val takeover: Boolean,
    /** Suggested quick replies rendered as tappable chips; empty means free text only. */
    val options: List<String> = emptyList(),
) {
    internal val answer = CompletableDeferred<String>()
}

class PendingApproval(val id: Long, val request: ApprovalRequest) {
    internal val answer = CompletableDeferred<ApprovalAnswer>()
}

/**
 * The one conversation with the agent: runs chat turns and phone tasks one at a time in [scope],
 * keeps the chat list, and is the agent's [UserChannel] (the next chat input answers a question)
 * and [ApprovalGateway] (the approval card / notification answers it).
 *
 * [AgentService][dev.xiaoman.app.AgentService] keeps the process in the foreground while [busy].
 *
 * Every phone task ends with a terminal event in the trace: when the agent cannot run (not
 * configured, busy, crashed, cancelled) the session emits `Failed` + `PhoneTaskFinished` itself.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val agent: () -> Agent?,
    private val runIds: RunIds,
    val stopSignal: MutableStopSignal,
    private val trace: AgentListener,
    private val onTaskEnd: (String) -> Unit = {},
    private val texts: () -> SessionTexts = { SessionTexts() },
    private val ready: suspend () -> Unit = {},
    private val historyStore: ChatHistoryStore? = null,
    private val onHistoryCleared: () -> Unit = {},
    private val onResult: (String) -> Unit = {},
) : UserChannel, ApprovalGateway {

    private val historyLock = Any()
    private var historyActive = false
    private var finalReply: String? = null
    private var taskResult: String? = null
    private val saved = historyStore?.load() ?: ChatHistory()
    private val ids = AtomicLong(saved.items.maxOfOrNull(ChatItem::id) ?: 0L)
    private val running = AtomicBoolean(false)

    private val _items = MutableStateFlow(
        if (saved.active) {
            (saved.items + ChatItem.Error(ids.incrementAndGet(), texts().interrupted)).takeLast(MAX_ITEMS)
        } else {
            saved.items.takeLast(MAX_ITEMS)
        },
    )

    init {
        if (saved.active) persist()
    }

    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Short text of the current step for the overlay and the notification. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Phone tasks started but without PhoneTaskFinished yet. */
    private val openRuns = LinkedHashSet<String>()

    /** Trace first, then the UI. */
    val listener = AgentListener { event ->
        try {
            trace.onEvent(event)
        } catch (e: Exception) {
            // A full disk must not stop the task.
        }
        synchronized(openRuns) {
            when (event) {
                is AgentEvent.PhoneTaskStarted -> openRuns += event.taskId
                is AgentEvent.PhoneTaskFinished -> openRuns -= event.taskId
                else -> Unit
            }
        }
        if (event is AgentEvent.PhoneTaskFinished) onTaskEnd(event.taskId)
        StepText.status(event)?.let { _status.value = it }
        StepText.chatItem(event, ids.incrementAndGet())?.let { item ->
            synchronized(historyLock) {
                if (historyActive) {
                    when {
                        event is AgentEvent.Reply && item is ChatItem.Assistant && item.text.isNotBlank() -> finalReply = item.text
                        event is AgentEvent.PhoneTaskFinished && item is ChatItem.Progress -> taskResult = item.text
                        event is AgentEvent.Failed && item is ChatItem.Error -> taskResult = item.text
                    }
                }
                add(item)
            }
        }
        _events.tryEmit(event)
    }

    sealed class Submit {
        data object Answered : Submit()
        data object Started : Submit()
        data object Busy : Submit()
        data object NotConfigured : Submit()
        data object Empty : Submit()
    }

    /** Chat input: answers a pending question, else starts a chat turn. */
    fun submit(text: String): Submit {
        val message = text.trim()
        if (message.isEmpty()) return Submit.Empty
        if (answerQuestion(message)) return Submit.Answered
        val agent = agent()
        if (agent == null) {
            add(ChatItem.User(ids.incrementAndGet(), message, System.currentTimeMillis()))
            add(ChatItem.Error(ids.incrementAndGet(), texts().notConfigured))
            return Submit.NotConfigured
        }
        val started = launch(null, message) { agent.handle(message, listener, stopSignal) }
        if (!started) return Submit.Busy
        return Submit.Started
    }

    fun answerQuestion(message: String, questionId: Long? = null): Boolean {
        val pending = _question.value ?: return false
        if (questionId != null && pending.id != questionId) return false
        if (!pending.answer.complete(message)) return false
        add(ChatItem.User(ids.incrementAndGet(), message, System.currentTimeMillis()))
        return true
    }

    /**
     * Runs one phone task directly (debug bridge). [runId] becomes the task id and trace folder;
     * an invalid or missing one is replaced. Returns false when it could not start; the trace then
     * already holds Failed + PhoneTaskFinished for that run.
     */
    fun runPhoneTask(goal: String, skill: String?, runId: String?): Boolean {
        val id = runId?.takeIf(RunIds::isValid) ?: runIds.generate()
        val agent = agent()
        if (agent == null) {
            fail(id, texts().notConfigured)
            return false
        }
        val started = launch(id) {
            runIds.prime(id)
            agent.runPhoneTask(goal, skill, listener, stopSignal)
        }
        if (!started) fail(id, texts().busy)
        return started
    }

    /** Emits Failed + PhoneTaskFinished for a run that cannot (or could not) complete. */
    fun fail(runId: String, message: String) {
        listener.onEvent(AgentEvent.Failed(runId, message))
        listener.onEvent(AgentEvent.PhoneTaskFinished(runId, false, message, 0))
    }

    /** Overlay Stop / notification Stop / debug STOP: cooperative stop, pending prompts released. */
    fun stop() {
        if (!_busy.value) return
        stopSignal.stop()
        _approval.value?.answer?.complete(ApprovalAnswer(approved = false))
        _question.value?.answer?.complete(texts().stopped)
        _status.value = texts().stopped
    }

    fun answerApproval(answer: ApprovalAnswer, approvalId: Long? = null) {
        val pending = _approval.value ?: return
        if (approvalId != null && pending.id != approvalId) return
        pending.answer.complete(answer)
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean, options: List<String>): String {
        val pending = PendingQuestion(ids.incrementAndGet(), taskId, question, takeover, options)
        if (stopSignal.stopped) return texts().stopped
        if (options.isNotEmpty()) add(ChatItem.Question(ids.incrementAndGet(), question, takeover))
        _question.value = pending
        try {
            return pending.answer.await()
        } finally {
            _question.compareAndSet(pending, null)
        }
    }

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        if (stopSignal.stopped) return ApprovalAnswer(approved = false)
        val pending = PendingApproval(ids.incrementAndGet(), request)
        val before = _status.value
        add(ChatItem.Question(ids.incrementAndGet(), request.prompt, takeover = false))
        _approval.value = pending
        _status.value = "等待确认：${request.prompt}"
        try {
            val answer = pending.answer.await()
            val line = when {
                !answer.approved -> texts().denied(request.prompt)
                answer.scope == dev.xiaoman.api.ApprovalScope.TASK -> texts().approvedTask(request.prompt)
                else -> texts().approvedOnce(request.prompt)
            }
            add(ChatItem.Progress(ids.incrementAndGet(), line, answer.approved))
            return answer
        } finally {
            _approval.compareAndSet(pending, null)
            if (!stopSignal.stopped) _status.value = before
        }
    }

    /** Starts [block] unless something is running. [runId] is pre-registered as open (debug runs). */
    private fun launch(runId: String?, userMessage: String? = null, block: suspend () -> Unit): Boolean {
        synchronized(historyLock) {
            if (!running.compareAndSet(false, true)) return false
            historyActive = true
            finalReply = null
            taskResult = null
            if (userMessage == null) persist() else add(ChatItem.User(ids.incrementAndGet(), userMessage, System.currentTimeMillis()))
        }
        stopSignal.reset()
        if (runId != null) synchronized(openRuns) { openRuns += runId }
        _status.value = null
        _busy.value = true
        scope.launch {
            var failure: String? = null
            try {
                ready()
                block()
            } catch (e: CancellationException) {
                failure = texts().stopped
                throw e
            } catch (e: Exception) {
                failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                withContext(NonCancellable) {
                    val open = synchronized(openRuns) { openRuns.toList().also { openRuns.clear() } }
                    open.forEach { fail(it, failure ?: texts().stopped) }
                    if (failure != null && open.isEmpty()) add(ChatItem.Error(ids.incrementAndGet(), failure))
                    _question.value?.answer?.cancel()
                    _approval.value?.answer?.cancel()
                    _question.value = null
                    _approval.value = null
                    _status.value = null
                    val result = synchronized(historyLock) {
                        historyActive = false
                        persist()
                        running.set(false)
                        _busy.value = false
                        failure ?: finalReply ?: taskResult
                    }
                    if (!stopSignal.stopped && !result.isNullOrBlank()) onResult(result)
                }
            }
        }
        return true
    }

    fun clearHistory(): Boolean = synchronized(historyLock) {
        if (running.get()) return false
        if (historyStore?.save(ChatHistory()) == false) return false
        _items.value = emptyList()
        onHistoryCleared()
        true
    }

    private fun add(item: ChatItem) = synchronized(historyLock) {
        _items.value = (_items.value + item).takeLast(MAX_ITEMS)
        persist()
    }

    private fun persist() {
        historyStore?.save(ChatHistory(_items.value, historyActive))
    }

    companion object {
        const val MAX_ITEMS = 500
    }
}
