package dev.deepagent.mobile.agent.core

import android.content.Context
import android.net.Uri
import dev.deepagent.mobile.agent.deepseek.DeepSeekImage
import dev.deepagent.mobile.agent.deepseek.DeepSeekRequest
import dev.deepagent.mobile.agent.deepseek.DeepSeekResponsesClient
import dev.deepagent.mobile.agent.deepseek.DeepSeekStreamEvent
import dev.deepagent.mobile.agent.deepseek.DeepSeekToolDefinition
import dev.deepagent.mobile.agent.github.GitHubActionsClient
import dev.deepagent.mobile.agent.github.GitHubActionsRequest
import dev.deepagent.mobile.agent.github.GitHubActionsResult
import dev.deepagent.mobile.agent.git.GitBranchRequest
import dev.deepagent.mobile.agent.git.GitCommitRequest
import dev.deepagent.mobile.agent.git.GitHubPullRequestClient
import dev.deepagent.mobile.agent.git.GitHubPullRequestResult
import dev.deepagent.mobile.agent.git.GitOperation
import dev.deepagent.mobile.agent.git.GitOperationResult
import dev.deepagent.mobile.agent.git.GitOperationState
import dev.deepagent.mobile.agent.git.GitOperationStatus
import dev.deepagent.mobile.agent.git.GitPullRequestRequest
import dev.deepagent.mobile.agent.git.GitPushRequest
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.AgentWorkspaceSnapshot
import dev.deepagent.mobile.agent.model.PendingPatchApproval
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.patch.PatchPreview
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.runtime.LocalLiteRunner
import dev.deepagent.mobile.agent.session.PersistedAgentSession
import dev.deepagent.mobile.agent.session.SessionDecisionRecord
import dev.deepagent.mobile.agent.session.SessionInvocationRecord
import dev.deepagent.mobile.agent.session.SessionRequestSummary
import dev.deepagent.mobile.agent.session.SessionStore
import dev.deepagent.mobile.agent.tools.AgentToolDefinition
import dev.deepagent.mobile.agent.tools.ToolExecutionResult
import dev.deepagent.mobile.agent.tools.ToolRouter
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import dev.deepagent.mobile.agent.workspace.WorkspaceSummary
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

private data class PendingPatch(
    val sessionId: String,
    val workspaceId: String,
    val argumentsJson: String,
    val preview: PatchPreview,
    val canApply: Boolean,
    val invocationId: String? = null,
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
    private val pullRequests = GitHubPullRequestClient()
    private val sessionStore = SessionStore(appContext)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val journalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val journalMutex = Mutex()

    private val _state = MutableStateFlow(AgentSessionState())
    override val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    override val events: StateFlow<List<AgentEvent>> = _events.asStateFlow()

    private val _workspace = MutableStateFlow(
        workspaceManager.current.value?.toAgentSnapshot(),
    )
    override val workspace: StateFlow<AgentWorkspaceSnapshot?> = _workspace.asStateFlow()

    private val _pendingPatch = MutableStateFlow<PendingPatch?>(null)
    private val _pendingApproval = MutableStateFlow<PendingPatchApproval?>(null)
    override val pendingApproval: StateFlow<PendingPatchApproval?> =
        _pendingApproval.asStateFlow()

    private val _gitState = MutableStateFlow(GitOperationState())
    override val git: StateFlow<GitOperationState> = _gitState.asStateFlow()

    private var activeJob: Job? = null
    private var patchApplyJob: Job? = null
    private var currentSessionId: String? = null
    private var currentRequestSummary: SessionRequestSummary? = null
    private val invocationRecords = mutableListOf<SessionInvocationRecord>()
    private val decisionRecords = mutableListOf<SessionDecisionRecord>()
    private var eventSequence: Long = 0L
    private var recoveryReason: String? = null
    @Volatile
    private var persistGeneration: Long = 0L
    @Volatile
    private var closed = false

    init {
        restoreLatestSession()
    }

    override suspend fun submit(request: AgentRequest) {
        check(!closed) { "AgentCore уже закрыт" }
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
        completeOpenInvocations(
            state = "CANCELLED",
            summary = "Сессия отменена пользователем",
        )
        clearPendingPatch()
        _state.value = _state.value.copy(
            status = AgentSessionStatus.CANCELLED,
            finishedAt = System.currentTimeMillis(),
            recoveryRequired = false,
        )
        recordDecision("SESSION", "CANCELLED", "Пользователь остановил сессию")
        append(AgentEventKind.INFO, "Сессия остановлена пользователем")
    }

    override fun clearEvents() {
        _events.value = emptyList()
        persistAsync()
    }

    override suspend fun importWorkspace(
        uri: String,
        displayName: String?,
    ): AgentWorkspaceSnapshot {
        check(!closed) { "AgentCore уже закрыт" }
        val normalizedUri = uri.trim()
        require(normalizedUri.isNotBlank()) { "URI workspace не задан" }
        val summary = workspaceManager.importUri(
            resolver = appContext.contentResolver,
            uri = Uri.parse(normalizedUri),
            displayName = displayName,
        )
        return summary.toAgentSnapshot().also { _workspace.value = it }
    }


    override suspend fun inspectGit(): GitOperationResult {
        check(!closed) { "AgentCore уже закрыт" }
        val result = toolRouter.inspectGit(workspaceIdForActions())
            .copy(sessionId = currentSessionId)
        publishGitResult(result)
        return result
    }

    override suspend fun createGitBranch(
        request: GitBranchRequest,
    ): GitOperationResult {
        return runGitWrite(
            operation = GitOperation.CREATE_BRANCH,
            requiredPermission = PermissionMode.GITHUB_WRITE,
            description = "Создание branch",
        ) {
            toolRouter.createGitBranch(workspaceIdForActions(), request)
        }
    }

    override suspend fun commitGit(
        request: GitCommitRequest,
    ): GitOperationResult {
        return runGitWrite(
            operation = GitOperation.COMMIT,
            requiredPermission = PermissionMode.GITHUB_WRITE,
            description = "Создание commit",
        ) {
            toolRouter.commitGit(workspaceIdForActions(), request)
        }
    }

    override suspend fun pushGit(
        request: GitPushRequest,
    ): GitOperationResult {
        return runGitWrite(
            operation = GitOperation.PUSH,
            requiredPermission = PermissionMode.GITHUB_WRITE,
            description = "Push branch",
        ) {
            toolRouter.pushGit(workspaceIdForActions(), request)
        }
    }

    override suspend fun createPullRequest(
        request: GitPullRequestRequest,
        githubToken: String,
    ): GitOperationResult {
        return runGitWrite(
            operation = GitOperation.CREATE_PULL_REQUEST,
            requiredPermission = PermissionMode.PR_CREATE,
            description = "Создание Pull Request",
        ) {
            when (val result = pullRequests.create(request, githubToken)) {
                is GitHubPullRequestResult.Created -> GitOperationResult(
                    operation = GitOperation.CREATE_PULL_REQUEST,
                    status = GitOperationStatus.SUCCEEDED,
                    summary = "Pull Request создан: #" + result.number,
                    branch = result.head,
                    headSha = result.headSha,
                    pullRequestNumber = result.number,
                    pullRequestUrl = result.url,
                )

                is GitHubPullRequestResult.Failed -> GitOperationResult(
                    operation = GitOperation.CREATE_PULL_REQUEST,
                    status = if (result.errorCode == "GITHUB_PR_UNKNOWN") {
                        GitOperationStatus.UNKNOWN
                    } else {
                        GitOperationStatus.FAILED
                    },
                    summary = result.message,
                    errorCode = result.errorCode,
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        activeJob?.cancel()
        patchApplyJob?.cancel()
        patchApplyJob = null
        coreScope.cancel()
        closed = true
        persistOnClose()
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
        _gitState.value = GitOperationState(sessionId = sessionId)
        eventSequence = 0L
        invocationRecords.clear()
        decisionRecords.clear()
        recoveryReason = null
        _events.value = emptyList()
        clearPendingPatch()
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
            completeOpenInvocations(
                state = "CANCELLED",
                summary = "Сессия отменена",
            )
            _state.value = _state.value.copy(
                status = AgentSessionStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                recoveryRequired = false,
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
            recordDecision(
                kind = "REMOTE_ACTION",
                state = "WAITING_APPROVAL",
                detail = "Требуется permission GITHUB_WRITE",
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
                val invocationId = beginInvocation(call.name, call.callId)
                append(
                    AgentEventKind.TOOL,
                    "Вызов tool: " + call.name,
                    "round=" + round + "; call_id=" + call.callId,
                    invocationId = invocationId,
                )

                if (call.name == ToolRouter.TOOL_APPLY_PATCH) {
                    val previewResult = toolRouter.previewPatch(
                        argumentsJson = call.arguments,
                        workspaceId = request.workspaceId,
                    )
                    completeInvocation(
                        invocationId = invocationId,
                        state = if (previewResult.ok) {
                            "WAITING_APPROVAL"
                        } else {
                            "FAILED"
                        },
                        summary = previewResult.summary,
                    )
                    appendToolResult(previewResult, invocationId)
                    val preview = previewResult.patchPreview
                    if (previewResult.ok && preview != null) {
                        val permission = currentRequestSummary?.permission
                            ?: PermissionMode.READ_ONLY
                        val pendingPatch = PendingPatch(
                            sessionId = currentSessionId.orEmpty(),
                            workspaceId = request.workspaceId.orEmpty(),
                            argumentsJson = call.arguments,
                            preview = preview,
                            canApply = permission >= PermissionMode.LOCAL_WRITE,
                            invocationId = invocationId,
                        )
                        _pendingPatch.value = pendingPatch
                        _pendingApproval.value = pendingPatch.toApproval()
                        _state.value = _state.value.copy(
                            status = AgentSessionStatus.WAITING_APPROVAL,
                            lastError = null,
                            recoveryRequired = false,
                        )
                        recordDecision(
                            kind = "PATCH",
                            state = "WAITING_APPROVAL",
                            detail = "Ожидается approval для " + preview.path,
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
                completeInvocation(
                    invocationId = invocationId,
                    state = if (toolResult.ok) "SUCCEEDED" else "FAILED",
                    summary = toolResult.summary,
                )
                appendToolResult(toolResult, invocationId)
                nextInput += JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", call.callId)
                    .put("output", toolResult.toModelJson())
            }
            inputItems = nextInput
        }
    }

    private fun appendToolResult(
        result: ToolExecutionResult,
        invocationId: String? = null,
    ) {
        append(
            AgentEventKind.TOOL,
            if (result.ok) {
                result.toolName + " завершён"
            } else {
                result.toolName + " отклонён: " + result.summary
            },
            result.toModelJson().take(MAX_EVENT_DETAIL_CHARS),
            invocationId = invocationId,
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

    override fun approvePendingPatch() {
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
            recoveryRequired = false,
        )
        recordDecision(
            kind = "PATCH",
            state = "APPROVED",
            detail = "Применение " + pending.preview.path,
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
                clearPendingPatch()
                completeInvocation(
                    invocationId = pending.invocationId,
                    state = "UNKNOWN",
                    summary = result.summary,
                )
                markUnknown(
                    result.summary + "; выполните новый preview перед продолжением",
                )
                return@launch
            }

            completeInvocation(
                invocationId = pending.invocationId,
                state = "SUCCEEDED",
                summary = "Patch применён",
            )
            recoveryReason = null
            clearPendingPatch()
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

    override fun rejectPendingPatch() {
        if (_state.value.status != AgentSessionStatus.WAITING_APPROVAL) return
        val pending = _pendingPatch.value ?: return
        patchApplyJob?.cancel()
        patchApplyJob = null
        completeInvocation(
            invocationId = pending.invocationId,
            state = "CANCELLED",
            summary = "Patch отклонён пользователем",
        )
        recordDecision(
            kind = "PATCH",
            state = "REJECTED",
            detail = "Отклонено: " + pending.preview.path,
        )
        clearPendingPatch()
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
        invocationRecords.clear()
        invocationRecords += restored.invocations
        decisionRecords.clear()
        decisionRecords += restored.decisions
        eventSequence = maxOf(
            restored.eventCursor,
            restored.events.maxOfOrNull { it.sequence } ?: 0L,
        )
        clearPendingPatch()

        val previousState = restored.state
        val requiresRecovery = previousState.status == AgentSessionStatus.RUNNING ||
            previousState.status == AgentSessionStatus.WAITING_APPROVAL
        val recoveryMessage =
            "Сессия восстановлена после незавершённой операции; требуется re-check"
        if (requiresRecovery) {
            val recoveryTimestamp = System.currentTimeMillis()
            invocationRecords.indices.forEach { index ->
                val invocation = invocationRecords[index]
                if (
                    invocation.state == "RUNNING" ||
                    invocation.state == "WAITING_APPROVAL"
                ) {
                    invocationRecords[index] = invocation.copy(
                        state = "UNKNOWN",
                        completedAt = recoveryTimestamp,
                        summary = "Результат операции не был подтверждён до остановки процесса",
                    )
                }
            }
        }
        recoveryReason = restored.recoveryReason
            ?: if (requiresRecovery) recoveryMessage else null
        val recoveredState = previousState.copy(
            status = if (requiresRecovery) {
                AgentSessionStatus.UNKNOWN
            } else {
                previousState.status
            },
            sessionId = previousState.sessionId ?: restored.sessionId,
            eventCursor = eventSequence,
            recoveryRequired = previousState.recoveryRequired || requiresRecovery,
            lastError = if (requiresRecovery) {
                recoveryMessage
            } else {
                previousState.lastError
            },
            finishedAt = if (requiresRecovery) {
                System.currentTimeMillis()
            } else {
                previousState.finishedAt
            },
        )
        _state.value = recoveredState
        _gitState.value = GitOperationState(sessionId = restored.sessionId)
        _events.value = restored.events.takeLast(MAX_EVENTS)

        if (requiresRecovery) {
            recordDecision(
                kind = "RECOVERY",
                state = "REQUIRED",
                detail = recoveryMessage,
            )
            append(
                AgentEventKind.INFO,
                "Восстановлена последняя сессия " + restored.sessionId.take(8),
                recoveryMessage,
            )
        }
    }

    private fun buildPersistedSnapshot(): PersistedAgentSession? {
        val sessionId = currentSessionId ?: return null
        val request = currentRequestSummary ?: return null
        return PersistedAgentSession(
            sessionId = sessionId,
            request = request,
            state = _state.value.copy(eventCursor = eventSequence),
            events = _events.value.toList(),
            updatedAt = System.currentTimeMillis(),
            eventCursor = eventSequence,
            invocations = invocationRecords.toList(),
            decisions = decisionRecords.toList(),
            recoveryReason = recoveryReason,
        )
    }

    private fun persistAsync() {
        if (closed) return
        val snapshot = buildPersistedSnapshot() ?: return
        val generation = persistGeneration + 1
        persistGeneration = generation
        journalScope.launch {
            if (closed || generation != persistGeneration) return@launch
            journalMutex.withLock {
                if (!closed && generation == persistGeneration) {
                    sessionStore.save(snapshot)
                }
            }
        }
    }

    private fun persistOnClose() {
        val snapshot = buildPersistedSnapshot()
        if (snapshot == null) {
            journalScope.cancel()
            return
        }
        journalScope.launch {
            try {
                journalMutex.withLock {
                    sessionStore.save(snapshot)
                }
            } finally {
                journalScope.cancel()
            }
        }
    }


    private suspend fun runGitWrite(
        operation: GitOperation,
        requiredPermission: PermissionMode,
        description: String,
        action: suspend () -> GitOperationResult,
    ): GitOperationResult {
        check(!closed) { "AgentCore уже закрыт" }
        if (_state.value.status == AgentSessionStatus.RUNNING) {
            val result = GitOperationResult(
                operation = operation,
                status = GitOperationStatus.FAILED,
                summary = "Сначала завершите текущую сессию Agent Core",
                errorCode = "SESSION_BUSY",
            )
            val boundResult = result.copy(sessionId = currentSessionId)
            publishGitResult(boundResult)
            return boundResult
        }

        val permission = currentRequestSummary?.permission ?: PermissionMode.READ_ONLY
        if (permission < requiredPermission) {
            val result = GitOperationResult(
                operation = operation,
                status = GitOperationStatus.FAILED,
                summary = "Для операции нужен permission " + requiredPermission.name,
                errorCode = "PERMISSION_REQUIRED",
            )
            recordDecision(
                kind = "GIT",
                state = "DENIED",
                detail = operation.name + "; required=" + requiredPermission.name,
            )
            val boundResult = result.copy(sessionId = currentSessionId)
            publishGitResult(boundResult)
            return boundResult
        }

        recordDecision(
            kind = "GIT",
            state = "APPROVED",
            detail = operation.name + "; user_action=true",
        )
        append(
            AgentEventKind.APPROVAL,
            "Пользователь подтвердил Git-операцию",
            "operation=" + operation.name,
        )
        _gitState.value = GitOperationState(
            status = GitOperationStatus.RUNNING,
            operation = operation.name,
            summary = description,
            updatedAt = System.currentTimeMillis(),
        )

        val result = try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GitOperationResult(
                operation = operation,
                status = GitOperationStatus.FAILED,
                summary = AgentRedactor.text(
                    error.message ?: "Git-операция завершилась с ошибкой",
                    MAX_ERROR_CHARS,
                ).orEmpty(),
                errorCode = "GIT_OPERATION_FAILED",
            )
        }
        val boundResult = result.copy(
            sessionId = result.sessionId ?: currentSessionId,
        )
        publishGitResult(boundResult)
        if (boundResult.status == GitOperationStatus.UNKNOWN) {
            markUnknown(
                boundResult.summary + "; повтор запрещён до re-check",
            )
        }
        return boundResult
    }

    private fun publishGitResult(result: GitOperationResult) {
        _gitState.value = GitOperationState(
            status = result.status,
            sessionId = result.sessionId ?: currentSessionId,
            operation = result.operation.name,
            summary = result.summary,
            branch = result.branch,
            headSha = result.headSha,
            workspaceFingerprintBefore = result.workspaceFingerprintBefore,
            workspaceFingerprintAfter = result.workspaceFingerprintAfter,
            errorCode = result.errorCode,
            pullRequestNumber = result.pullRequestNumber,
            pullRequestUrl = result.pullRequestUrl,
            updatedAt = System.currentTimeMillis(),
        )
        val kind = when (result.status) {
            GitOperationStatus.FAILED,
            GitOperationStatus.UNKNOWN,
            -> AgentEventKind.ERROR
            else -> AgentEventKind.TOOL
        }
        append(
            kind,
            result.summary,
            result.toJson().toString(),
        )
    }

    private fun workspaceIdForActions(): String? {
        return currentRequestSummary?.workspaceId
            ?: workspaceManager.current.value?.id
    }

    private fun clearPendingPatch() {
        _pendingPatch.value = null
        _pendingApproval.value = null
    }

    private fun PendingPatch.toApproval(): PendingPatchApproval {
        return PendingPatchApproval(
            sessionId = sessionId,
            workspaceId = workspaceId,
            path = preview.path,
            workspaceFingerprint = preview.workspaceFingerprint,
            oldSha256 = preview.oldSha256,
            newSha256 = preview.newSha256,
            unifiedDiff = AgentRedactor.text(
                preview.unifiedDiff,
                MAX_EVENT_DETAIL_CHARS,
            ).orEmpty(),
            canApply = canApply,
        )
    }

    private fun WorkspaceSummary.toAgentSnapshot(): AgentWorkspaceSnapshot {
        return AgentWorkspaceSnapshot(
            id = id,
            displayName = displayName,
            sourceType = sourceType,
            fileCount = fileCount,
            totalBytes = totalBytes,
            importedAt = importedAt,
        )
    }

    private fun completeOpenInvocations(
        state: String,
        summary: String,
    ) {
        val completedAt = System.currentTimeMillis()
        invocationRecords.indices.forEach { index ->
            val invocation = invocationRecords[index]
            if (
                invocation.state == "RUNNING" ||
                invocation.state == "WAITING_APPROVAL"
            ) {
                invocationRecords[index] = invocation.copy(
                    state = state.take(64),
                    completedAt = completedAt,
                    summary = AgentRedactor.text(
                        summary,
                        SessionInvocationRecord.MAX_SUMMARY_CHARS,
                    ),
                )
            }
        }
    }

    private fun beginInvocation(toolName: String, callId: String?): String {
        val invocationId = UUID.randomUUID().toString()
        invocationRecords += SessionInvocationRecord(
            invocationId = invocationId,
            toolName = toolName,
            callId = callId,
            state = "RUNNING",
        )
        return invocationId
    }

    private fun completeInvocation(
        invocationId: String?,
        state: String,
        summary: String?,
    ) {
        if (invocationId == null) return
        val index = invocationRecords.indexOfFirst {
            it.invocationId == invocationId
        }
        if (index < 0) return
        val current = invocationRecords[index]
        invocationRecords[index] = current.copy(
            state = state.take(64),
            completedAt = System.currentTimeMillis(),
            summary = AgentRedactor.text(
                summary,
                SessionInvocationRecord.MAX_SUMMARY_CHARS,
            ),
        )
    }

    private fun recordDecision(
        kind: String,
        state: String,
        detail: String?,
    ) {
        decisionRecords += SessionDecisionRecord(
            decisionId = UUID.randomUUID().toString(),
            kind = kind,
            state = state,
            detail = detail,
        )
        persistAsync()
    }

    private fun resolveTarget(request: AgentRequest): ExecutionTarget {
        if (request.target != ExecutionTarget.AUTO) return request.target

        val remoteHint = Regex(
            "(?i)\\b(apk|aab|gradle|android sdk|ndk|cmake|compile|build|сборк)\\b",
        ).containsMatchIn(request.task)

        return if (remoteHint) ExecutionTarget.REMOTE_ACTIONS else ExecutionTarget.LOCAL_LITE
    }

    private fun fail(message: String) {
        completeOpenInvocations(
            state = "FAILED",
            summary = message,
        )
        recoveryReason = null
        _state.value = _state.value.copy(
            status = AgentSessionStatus.FAILED,
            lastError = AgentRedactor.text(message, MAX_ERROR_CHARS),
            finishedAt = System.currentTimeMillis(),
            recoveryRequired = false,
        )
        append(AgentEventKind.ERROR, message)
    }

    private fun markUnknown(message: String) {
        completeOpenInvocations(
            state = "UNKNOWN",
            summary = message,
        )
        recoveryReason = AgentRedactor.text(message, MAX_ERROR_CHARS)
        _state.value = _state.value.copy(
            status = AgentSessionStatus.UNKNOWN,
            lastError = recoveryReason,
            finishedAt = System.currentTimeMillis(),
            recoveryRequired = true,
        )
        append(AgentEventKind.ERROR, message)
    }

    private fun append(
        kind: AgentEventKind,
        message: String,
        detail: String? = null,
        invocationId: String? = null,
    ) {
        eventSequence += 1
        val next = _events.value + AgentEvent(
            kind = kind,
            message = AgentRedactor.text(message, MAX_EVENT_MESSAGE_CHARS).orEmpty(),
            detail = AgentRedactor.text(detail, MAX_EVENT_DETAIL_CHARS),
            createdAt = System.currentTimeMillis(),
            sessionId = currentSessionId,
            eventId = UUID.randomUUID().toString(),
            sequence = eventSequence,
            workspaceId = _state.value.workspaceId,
            invocationId = invocationId,
        )
        _events.value = next.takeLast(MAX_EVENTS)
        _state.value = _state.value.copy(eventCursor = eventSequence)
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
        const val MAX_EVENT_MESSAGE_CHARS = 8_000
        const val MAX_EVENT_DETAIL_CHARS = 12_000
        const val MAX_ERROR_CHARS = 4_000
    }
}
