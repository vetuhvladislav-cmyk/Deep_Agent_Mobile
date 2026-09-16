package dev.deepagent.mobile.agent.core

import android.content.Context
import dev.deepagent.mobile.agent.deepseek.DeepSeekImage
import dev.deepagent.mobile.agent.deepseek.DeepSeekRequest
import dev.deepagent.mobile.agent.deepseek.DeepSeekResponsesClient
import dev.deepagent.mobile.agent.deepseek.DeepSeekStreamEvent
import dev.deepagent.mobile.agent.deepseek.DeepSeekToolDefinition
import dev.deepagent.mobile.agent.github.GitHubActionsClient
import dev.deepagent.mobile.agent.github.GitHubActionsRequest
import dev.deepagent.mobile.agent.github.GitHubActionsResult
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.patch.PatchPreview
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.runtime.LocalLiteRunner
import dev.deepagent.mobile.agent.session.PersistedAgentSession
import dev.deepagent.mobile.agent.session.SessionRequestSummary
import dev.deepagent.mobile.agent.session.SessionStore
import dev.deepagent.mobile.agent.tools.AgentToolDefinition
import dev.deepagent.mobile.agent.tools.ToolExecutionResult
import dev.deepagent.mobile.agent.tools.ToolRouter
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

data class PendingPatch(
    val sessionId: String,
    val workspaceId: String,
    val argumentsJson: String,
    val preview: PatchPreview,
    val canApply: Boolean,
)

/**
 * Internal orchestrator for the single APK.
 *
 * P0 provides the bounded read-only agent loop:
 * workspace -> function_call -> allowlisted tool -> function_call_output.
 * P1-A adds preview-only apply_patch plus a host-side approval gate; the model
 * never writes directly and GitHub mutation/PR operations remain separate.
 */
class AgentCore(context: Context) : AgentBridge {

    private val appContext = context.applicationContext
    private val workspaceManager = WorkspaceManager(appContext)
    private val localRunner = LocalLiteRunner(appContext, workspaceManager)
    private val toolRouter = ToolRouter(workspaceManager)
    private val deepSeek = DeepSeekResponsesClient()
    private val actions = GitHubActionsClient()
    private val sessionStore = SessionStore(appContext)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val journalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val journalMutex = Mutex()

    private val _state = MutableStateFlow(AgentSessionState())
    override val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    override val events: StateFlow<List<AgentEvent>> = _events.asStateFlow()

    private val _pendingPatch = MutableStateFlow<PendingPatch?>(null)
    val pendingPatch: StateFlow<PendingPatch?> = _pendingPatch.asStateFlow()

    private var activeJob: Job? = null
    private var patchApplyJob: Job? = null
    private var currentSessionId: String? = null
    private var currentRequestSummary: SessionRequestSummary? = null
    @Volatile
    private var persistGeneration: Long = 0L

    val workspace: WorkspaceManager
        get() = workspaceManager

    init {
        restoreLatestSession()
    }

    override suspend fun submit(request: AgentRequest) {
        activeJob?.cancel()
        patchApplyJob?.cancel()
        patchApplyJob = null

        val job = coreScope.launch {
            execute(request)
        }
        activeJob = job

        try {
            job.join()
        } finally {
            if (activeJob === job) activeJob = null
        }
    }

    override fun cancel() {
        activeJob?.cancel()
        patchApplyJob?.cancel()
        patchApplyJob = null
        _pendingPatch.value = null
        _state.value = _state.value.copy(
            status = AgentSessionStatus.CANCELLED,
            finishedAt = System.currentTimeMillis(),
        )
        append(AgentEventKind.INFO, "Сессия остановлена пользователем")
    }

    override fun clearEvents() {
        _events.value = emptyList()
        persistAsync()
    }

    fun close() {
        activeJob?.cancel()
        patchApplyJob?.cancel()
        patchApplyJob = null
        coreScope.cancel()
        persistAsync()
    }

    private suspend fun execute(request: AgentRequest) {
        val task = request.task.trim()
        if (task.isBlank()) {
            fail("Задача не может быть пустой")
            return
        }

        val target = resolveTarget(request)
        val workspaceId = request.workspaceId ?: workspaceManager.current.value?.id
        val sessionId = request.sessionId ?: UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        currentSessionId = sessionId
        _pendingPatch.value = null
        currentRequestSummary = SessionRequestSummary(
            task = task,
            target = target,
            permission = request.permission,
            workspaceId = workspaceId,
            repository = request.repository,
            workflow = request.workflow,
            ref = request.ref,
        )
        _state.value = AgentSessionState(
            status = AgentSessionStatus.RUNNING,
            target = target,
            task = task,
            startedAt = startedAt,
            sessionId = sessionId,
            workspaceId = workspaceId,
        )
        persistAsync()

        append(AgentEventKind.SESSION, "Сессия Agent Core запущена")
        append(
            AgentEventKind.PLAN,
            "Исполнитель: " + target.label(),
            "permission=" + request.permission.name +
                "; workspace=" + (workspaceId ?: "не выбран"),
        )

        val boundRequest = request.copy(
            task = task,
            workspaceId = workspaceId,
            sessionId = sessionId,
        )

        try {
            when (target) {
                ExecutionTarget.LOCAL_LITE -> executeLocal(boundRequest)
                ExecutionTarget.REMOTE_ACTIONS -> executeRemote(boundRequest)
                ExecutionTarget.AUTO -> error("AUTO должен быть разрешён до запуска")
            }

            if (_state.value.status == AgentSessionStatus.RUNNING) {
                _state.value = _state.value.copy(
                    status = AgentSessionStatus.COMPLETED,
                    finishedAt = System.currentTimeMillis(),
                )
                append(AgentEventKind.SESSION, "Сессия завершена")
            }
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(
                status = AgentSessionStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
            )
            append(AgentEventKind.INFO, "Сессия отменена")
        } catch (error: Exception) {
            fail(error.message ?: "Неизвестная ошибка Agent Core")
        }
    }

    private suspend fun executeLocal(request: AgentRequest) {
        append(AgentEventKind.TOOL, "Local Lite Runner: health probe")
        val probe = localRunner.probe()

        append(
            AgentEventKind.TOOL,
            if (probe.exitCode == 0) {
                "Local Lite Runner готов (" + probe.durationMs + " ms)"
            } else {
                "Local Lite Runner завершился с кодом " + probe.exitCode
            },
            listOf(probe.stdout, probe.stderr)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .take(MAX_EVENT_DETAIL_CHARS),
        )

        runDeepSeekAgent(request)
    }

    private suspend fun executeRemote(request: AgentRequest) {
        runDeepSeekAgent(request)
        if (_state.value.status != AgentSessionStatus.RUNNING) return

        if (request.permission < PermissionMode.GITHUB_WRITE) {
            _state.value = _state.value.copy(
                status = AgentSessionStatus.WAITING_APPROVAL,
            )
            append(
                AgentEventKind.INFO,
                "Запуск GitHub Actions ожидает разрешения GITHUB_WRITE",
            )
            return
        }

        append(
            AgentEventKind.BUILD,
            "Запуск удалённой сборки GitHub Actions",
            (request.repository ?: "repository не задан") + " / " +
                (request.workflow ?: "workflow не задан") + " @ " + request.ref,
        )

        val result = actions.dispatch(
            GitHubActionsRequest(
                token = request.githubToken.orEmpty(),
                repository = request.repository.orEmpty(),
                workflow = request.workflow.orEmpty(),
                ref = request.ref,
                inputs = mapOf("agent_task" to request.task.take(2_000)),
            ),
        )

        when (result) {
            GitHubActionsResult.Dispatched -> {
                append(
                    AgentEventKind.BUILD,
                    "GitHub Actions workflow поставлен в очередь",
                )
            }

            is GitHubActionsResult.NotConfigured -> {
                fail(result.message)
            }

            is GitHubActionsResult.Failed -> {
                fail(result.message)
            }
        }
    }

    private suspend fun runDeepSeekAgent(request: AgentRequest) {
        val apiKey = request.deepSeekApiKey?.trim().orEmpty()
        if (apiKey.isBlank()) {
            append(
                AgentEventKind.OUTPUT,
                "Offline prototype: DeepSeek API key не задан",
                "Локальный контракт и маршрутизация проверены; для реального ответа " +
                    "укажите ключ DeepSeek в Agent Console.",
            )
            return
        }

        val selectedWorkspace = request.workspaceId
            ?.let { workspaceManager.resolveRoot(it) }
        val definitions = if (selectedWorkspace != null) {
            ToolRouter.definitions()
        } else {
            emptyList()
        }

        if (selectedWorkspace == null) {
            append(
                AgentEventKind.INFO,
                "Workspace не выбран; read-only tools отключены",
            )
        } else {
            append(
                AgentEventKind.INFO,
                "Read-only ToolRouter активен",
                "workspace=" + request.workspaceId,
            )
        }

        append(
            AgentEventKind.SESSION,
            "DeepSeek " + request.model + " streaming запущен",
        )

        var inputItems = emptyList<JSONObject>()
        var round = 0

        while (true) {
            val result = deepSeek.streamRound(
                DeepSeekRequest(
                    apiKey = apiKey,
                    baseUrl = request.deepSeekBaseUrl,
                    model = request.model,
                    task = request.task,
                    image = request.image?.let {
                        DeepSeekImage(it.dataUrl, it.detail)
                    },
                    inputItems = inputItems,
                    tools = definitions.map { it.toDeepSeekDefinition() },
                ),
            ) { event ->
                when (event) {
                    is DeepSeekStreamEvent.ReasoningDelta -> {
                        append(AgentEventKind.REASONING, event.text)
                    }

                    is DeepSeekStreamEvent.OutputDelta -> {
                        append(AgentEventKind.OUTPUT, event.text)
                    }

                    is DeepSeekStreamEvent.ToolArgumentsDelta -> {
                        append(
                            AgentEventKind.TOOL,
                            "Получены аргументы tool call",
                            event.text.take(MAX_EVENT_DETAIL_CHARS),
                        )
                    }

                    is DeepSeekStreamEvent.Completed -> {
                        append(AgentEventKind.SESSION, "DeepSeek response round завершён")
                    }

                    is DeepSeekStreamEvent.Failed -> {
                        fail(event.message)
                    }
                }
            }

            if (result.failure != null) {
                if (_state.value.status != AgentSessionStatus.FAILED) {
                    fail(result.failure)
                }
                return
            }
            if (result.response == null) {
                fail("DeepSeek не вернул response")
                return
            }
            if (result.functionCalls.isEmpty()) return

            round += 1
            if (round > MAX_TOOL_ROUNDS) {
                fail("Достигнут лимит read-only tool rounds")
                return
            }

            val nextInput = responseOutputItems(result.response).toMutableList()
            for (call in result.functionCalls) {
                append(
                    AgentEventKind.TOOL,
                    "Вызов tool: " + call.name,
                    "round=" + round + "; call_id=" + call.callId,
                )

                if (call.name == ToolRouter.TOOL_APPLY_PATCH) {
                    val previewResult = toolRouter.previewPatch(
                        argumentsJson = call.arguments,
                        workspaceId = request.workspaceId,
                    )
                    appendToolResult(previewResult)
                    val preview = previewResult.patchPreview
                    if (previewResult.ok && preview != null) {
                        val permission = currentRequestSummary?.permission
                            ?: PermissionMode.READ_ONLY
                        _pendingPatch.value = PendingPatch(
                            sessionId = currentSessionId.orEmpty(),
                            workspaceId = request.workspaceId.orEmpty(),
                            argumentsJson = call.arguments,
                            preview = preview,
                            canApply = permission >= PermissionMode.LOCAL_WRITE,
                        )
                        _state.value = _state.value.copy(
                            status = AgentSessionStatus.WAITING_APPROVAL,
                            lastError = null,
                        )
                        append(
                            AgentEventKind.INFO,
                            "Patch preview готов; запись приостановлена до approval",
                            "path=" + preview.path +
                                "; workspace_fingerprint=" +
                                preview.workspaceFingerprint +
                                "; can_apply=" +
                                (permission >= PermissionMode.LOCAL_WRITE),
                        )
                        return
                    }
                    nextInput += JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", call.callId)
                        .put("output", previewResult.toModelJson())
                    continue
                }

                val toolResult = toolRouter.execute(
                    toolName = call.name,
                    argumentsJson = call.arguments,
                    workspaceId = request.workspaceId,
                )
                appendToolResult(toolResult)
                nextInput += JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", call.callId)
                    .put("output", toolResult.toModelJson())
            }
            inputItems = nextInput
        }
    }

    private fun appendToolResult(result: ToolExecutionResult) {
        append(
            AgentEventKind.TOOL,
            if (result.ok) {
                result.toolName + " завершён"
            } else {
                result.toolName + " отклонён: " + result.summary
            },
            result.toModelJson().take(MAX_EVENT_DETAIL_CHARS),
        )
    }

    private fun responseOutputItems(response: JSONObject): List<JSONObject> {
        val output = response.optJSONArray("output") ?: return emptyList()
        return buildList {
            for (index in 0 until output.length()) {
                output.optJSONObject(index)?.let(::add)
            }
        }
    }

    fun approvePendingPatch() {
        if (_state.value.status != AgentSessionStatus.WAITING_APPROVAL) return
        val pending = _pendingPatch.value ?: return
        if (!pending.canApply) {
            append(
                AgentEventKind.ERROR,
                "Patch отклонён: для этой сессии не подтверждён permission LOCAL_WRITE",
            )
            return
        }

        _state.value = _state.value.copy(
            status = AgentSessionStatus.RUNNING,
            lastError = null,
        )
        append(
            AgentEventKind.INFO,
            "Пользователь подтвердил применение patch",
            "path=" + pending.preview.path +
                "; workspace_fingerprint=" + pending.preview.workspaceFingerprint,
        )

        patchApplyJob?.cancel()
        val job = coreScope.launch {
            val result = toolRouter.applyPatch(
                argumentsJson = pending.argumentsJson,
                workspaceId = pending.workspaceId,
                expectedWorkspaceFingerprint = pending.preview.workspaceFingerprint,
            )
            if (!result.ok) {
                _pendingPatch.value = null
                markUnknown(
                    result.summary + "; выполните новый preview перед продолжением",
                )
                return@launch
            }

            _pendingPatch.value = null
            _state.value = _state.value.copy(
                status = AgentSessionStatus.COMPLETED,
                finishedAt = System.currentTimeMillis(),
                lastError = null,
            )
            append(
                AgentEventKind.TOOL,
                "apply_patch применён после явного approval",
                result.toModelJson(),
            )
            append(AgentEventKind.SESSION, "Сессия завершена после применения patch")
        }
        patchApplyJob = job
        job.invokeOnCompletion {
            if (patchApplyJob === job) patchApplyJob = null
        }
    }

    fun rejectPendingPatch() {
        if (_state.value.status != AgentSessionStatus.WAITING_APPROVAL) return
        if (_pendingPatch.value == null) return
        patchApplyJob?.cancel()
        patchApplyJob = null
        _pendingPatch.value = null
        _state.value = _state.value.copy(
            status = AgentSessionStatus.CANCELLED,
            finishedAt = System.currentTimeMillis(),
        )
        append(AgentEventKind.INFO, "Patch отклонён пользователем")
    }

    private fun restoreLatestSession() {
        val restored = sessionStore.loadLatest() ?: return
        currentSessionId = restored.sessionId
        currentRequestSummary = restored.request
        val previousState = restored.state
        val recoveredState = if (
            previousState.status == AgentSessionStatus.RUNNING ||
            previousState.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            previousState.copy(
                status = AgentSessionStatus.UNKNOWN,
                lastError = "Сессия восстановлена после незавершённой операции; требуется re-check",
                finishedAt = System.currentTimeMillis(),
            )
        } else {
            previousState
        }
        _state.value = recoveredState
        _events.value = restored.events + AgentEvent(
            kind = AgentEventKind.INFO,
            message = "Восстановлена последняя сессия " + restored.sessionId.take(8),
            detail = if (recoveredState.status == AgentSessionStatus.UNKNOWN) {
                "Незавершённая операция не запущена повторно; требуется новый preview/re-check."
            } else {
                null
            },
            sessionId = restored.sessionId,
        )
    }

    private fun persistAsync() {
        val sessionId = currentSessionId ?: return
        val request = currentRequestSummary ?: return
        val generation = persistGeneration + 1
        persistGeneration = generation
        val snapshot = PersistedAgentSession(
            sessionId = sessionId,
            request = request,
            state = _state.value,
            events = _events.value.takeLast(PersistedAgentSession.MAX_EVENTS),
            updatedAt = System.currentTimeMillis(),
        )
        journalScope.launch {
            if (generation != persistGeneration) return@launch
            journalMutex.withLock {
                if (generation == persistGeneration) sessionStore.save(snapshot)
            }
        }
    }

    private fun resolveTarget(request: AgentRequest): ExecutionTarget {
        if (request.target != ExecutionTarget.AUTO) return request.target

        val remoteHint = Regex(
            "(?i)\\b(apk|aab|gradle|android sdk|ndk|cmake|compile|build|сборк)\\b",
        ).containsMatchIn(request.task)

        return if (remoteHint) ExecutionTarget.REMOTE_ACTIONS else ExecutionTarget.LOCAL_LITE
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            status = AgentSessionStatus.FAILED,
            lastError = message,
            finishedAt = System.currentTimeMillis(),
        )
        append(AgentEventKind.ERROR, message)
    }

    private fun markUnknown(message: String) {
        _state.value = _state.value.copy(
            status = AgentSessionStatus.UNKNOWN,
            lastError = message,
            finishedAt = System.currentTimeMillis(),
        )
        append(AgentEventKind.ERROR, message)
    }

    private fun append(kind: AgentEventKind, message: String, detail: String? = null) {
        val next = _events.value + AgentEvent(
            kind = kind,
            message = message,
            detail = detail?.take(MAX_EVENT_DETAIL_CHARS),
            sessionId = currentSessionId,
        )
        _events.value = next.takeLast(MAX_EVENTS)
        persistAsync()
    }

    private fun AgentToolDefinition.toDeepSeekDefinition(): DeepSeekToolDefinition {
        return DeepSeekToolDefinition(
            name = name,
            description = description,
            parameters = parameters,
        )
    }

    private fun ExecutionTarget.label(): String = when (this) {
        ExecutionTarget.AUTO -> "AUTO"
        ExecutionTarget.LOCAL_LITE -> "LOCAL_LITE"
        ExecutionTarget.REMOTE_ACTIONS -> "REMOTE_ACTIONS"
    }

    private companion object {
        const val MAX_EVENTS = 500
        const val MAX_TOOL_ROUNDS = 4
        const val MAX_EVENT_DETAIL_CHARS = 12_000
    }
}
