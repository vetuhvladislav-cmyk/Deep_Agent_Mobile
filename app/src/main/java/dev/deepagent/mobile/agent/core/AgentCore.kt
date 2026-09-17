package dev.deepagent.mobile.agent.core

import android.content.Context
import android.net.Uri
import dev.deepagent.mobile.agent.deepseek.DeepSeekRequest
import dev.deepagent.mobile.agent.deepseek.DeepSeekStreamEvent
import dev.deepagent.mobile.agent.deepseek.DeepSeekToolDefinition
import dev.deepagent.mobile.agent.github.GitHubActionsClient
import dev.deepagent.mobile.agent.image.ImageAnalysisPipeline
import dev.deepagent.mobile.agent.credential.CredentialKind
import dev.deepagent.mobile.agent.credential.EphemeralCredentialVault
import dev.deepagent.mobile.agent.model.CredentialState
import dev.deepagent.mobile.agent.model.JournalExportResult
import dev.deepagent.mobile.agent.model.JournalExportStatus
import dev.deepagent.mobile.agent.model.WorkspaceCatalogState
import dev.deepagent.mobile.agent.model.WorkspaceCatalogStatus
import dev.deepagent.mobile.agent.model.WorkspaceRulesMetadata
import dev.deepagent.mobile.agent.github.GitHubActionsRequest
import dev.deepagent.mobile.agent.github.GitHubActionsResult
import dev.deepagent.mobile.agent.github.ArtifactDownloadResult
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
import dev.deepagent.mobile.agent.artifact.ArtifactManager
import dev.deepagent.mobile.agent.model.ActionsArtifactRequest
import dev.deepagent.mobile.agent.model.ActionsArtifactSaveResult
import dev.deepagent.mobile.agent.model.ActionsArtifactSaveStatus
import dev.deepagent.mobile.agent.model.ActionsOperationState
import dev.deepagent.mobile.agent.model.ActionsOperationStatus
import dev.deepagent.mobile.agent.model.ActionsRunRequest
import dev.deepagent.mobile.agent.model.RuntimeState
import dev.deepagent.mobile.agent.model.RuntimeStatus
import dev.deepagent.mobile.agent.model.InteractiveCommandRequest
import dev.deepagent.mobile.agent.model.InteractiveSessionState
import dev.deepagent.mobile.agent.model.InteractiveSessionStatus
import dev.deepagent.mobile.agent.model.ImageAnalysisState
import dev.deepagent.mobile.agent.model.ImageAnalysisStatus
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.AgentWorkspaceSnapshot
import dev.deepagent.mobile.agent.plan.BoundedPlanner
import dev.deepagent.mobile.agent.plan.CompletionDecision
import dev.deepagent.mobile.agent.plan.CompletionEvaluator
import dev.deepagent.mobile.agent.provider.DeepSeekLlmProvider
import dev.deepagent.mobile.agent.provider.ProviderRegistry
import dev.deepagent.mobile.agent.model.ApprovalToken
import dev.deepagent.mobile.agent.model.ApprovalTokenFactory
import dev.deepagent.mobile.agent.model.PendingPatchApproval
import dev.deepagent.mobile.agent.model.PatchRecoveryState
import dev.deepagent.mobile.agent.model.PatchRecoveryStatus
import dev.deepagent.mobile.agent.model.PatchRollbackResult
import dev.deepagent.mobile.agent.model.PatchRollbackStatus
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.patch.PatchPreview
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.runtime.LocalLiteRunner
import dev.deepagent.mobile.agent.runtime.RuntimeSupervisor
import dev.deepagent.mobile.agent.runtime.InteractiveCommandSession
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URL
import java.util.LinkedHashMap
import java.util.UUID

private data class PendingPatch(
    val sessionId: String,
    val workspaceId: String,
    val argumentsJson: String,
    val preview: PatchPreview,
    val canApply: Boolean,
    val approvalToken: ApprovalToken,
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
    private val runtimeSupervisor = RuntimeSupervisor()
    private val interactiveSession = InteractiveCommandSession(workspaceManager)
    private val toolRouter = ToolRouter(workspaceManager)
    private val providerRegistry = ProviderRegistry(
        listOf(DeepSeekLlmProvider()),
    )
    private val actionsClient = GitHubActionsClient()
    private val artifactManager = ArtifactManager()
    private val imagePipeline = ImageAnalysisPipeline(appContext)
    private val credentialVault = EphemeralCredentialVault()
    private val pullRequests = GitHubPullRequestClient()
    private val sessionStore = SessionStore(appContext)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val journalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val submitMutex = Mutex()
    private val journalMutex = Mutex()
    private val externalMutationMutex = Mutex()
    private val gitOperationCache = LinkedHashMap<String, GitOperationResult>()
    private val actionsOperationCache = LinkedHashMap<String, ActionsOperationState>()
    private var lastGitResult: GitOperationResult? = null

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

    private val _patchRecovery = MutableStateFlow<PatchRecoveryState?>(null)
    override val patchRecovery: StateFlow<PatchRecoveryState?> =
        _patchRecovery.asStateFlow()

    private val _actionsState = MutableStateFlow(ActionsOperationState())
    override val actions: StateFlow<ActionsOperationState> =
        _actionsState.asStateFlow()

    private val _runtimeState = MutableStateFlow(RuntimeState())
    override val runtime: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _interactiveState = MutableStateFlow(InteractiveSessionState())
    override val interactive: StateFlow<InteractiveSessionState> =
        _interactiveState.asStateFlow()

    private val _imageState = MutableStateFlow(ImageAnalysisState())
    override val image: StateFlow<ImageAnalysisState> = _imageState.asStateFlow()

    private val _workspaceCatalog = MutableStateFlow(
        WorkspaceCatalogState(
            selectedId = workspaceManager.current.value?.id,
            items = workspaceManager.list().map { it.toAgentSnapshot() },
            status = WorkspaceCatalogStatus.READY,
            summary = "Workspace catalog готов",
        ),
    )
    override val workspaceCatalog: StateFlow<WorkspaceCatalogState> =
        _workspaceCatalog.asStateFlow()

    private val _credentialState = MutableStateFlow(CredentialState())
    override val credentials: StateFlow<CredentialState> = _credentialState.asStateFlow()

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
        coreScope.launch { refreshWorkspace() }
    }

    override suspend fun submit(request: AgentRequest) {
        val job = submitMutex.withLock {
            check(!closed) { "AgentCore уже закрыт" }
            validateRequest(request)
            val sanitizedRequest = request.copy(
                deepSeekApiKey = null,
                githubToken = null,
            )
            actionsClient.cancelActive()
            pullRequests.cancelActive()
            activeJob?.cancelAndJoin()
            patchApplyJob?.cancelAndJoin()
            activeJob = null
            patchApplyJob = null

            if (
                !request.deepSeekApiKey.isNullOrBlank() ||
                !request.githubToken.isNullOrBlank()
            ) {
                configureCredentials(
                    deepSeekApiKey = request.deepSeekApiKey,
                    githubToken = request.githubToken,
                )
            }

            coreScope.launch {
                execute(sanitizedRequest)
            }.also { activeJob = it }
        }

        try {
            job.join()
        } finally {
            if (activeJob === job) activeJob = null
        }
    }

    override fun configureCredentials(
        deepSeekApiKey: String?,
        githubToken: String?,
    ): CredentialState {
        check(!closed) { "AgentCore уже закрыт" }
        val next = credentialVault.replace(deepSeekApiKey, githubToken)
        _credentialState.value = next
        if (currentSessionId != null) {
            append(
                AgentEventKind.INFO,
                "Временные credentials обновлены",
                next.toJson().toString(),
            )
        }
        return next
    }

    override fun clearCredentials() {
        if (closed) return
        val next = credentialVault.clearAll()
        _credentialState.value = next
        if (currentSessionId != null) {
            append(
                AgentEventKind.INFO,
                "Временные credentials очищены",
                next.toJson().toString(),
            )
        }
    }

    override suspend fun exportJournal(destinationUri: String): JournalExportResult =
        withContext(Dispatchers.IO) {
            if (closed) {
                return@withContext JournalExportResult(
                    sessionId = currentSessionId,
                    status = JournalExportStatus.FAILED,
                    summary = "AgentCore уже закрыт",
                    errorCode = "AGENT_CORE_CLOSED",
                )
            }
            val normalizedUri = destinationUri.trim()
            if (normalizedUri.isBlank()) {
                return@withContext JournalExportResult(
                    sessionId = currentSessionId,
                    status = JournalExportStatus.FAILED,
                    summary = "URI для экспорта журнала не задан",
                    errorCode = "JOURNAL_DESTINATION_INVALID",
                )
            }
            val uri = runCatching { Uri.parse(normalizedUri) }.getOrNull()
            if (uri == null || uri.scheme.isNullOrBlank()) {
                return@withContext JournalExportResult(
                    sessionId = currentSessionId,
                    status = JournalExportStatus.FAILED,
                    summary = "URI для экспорта журнала недействителен",
                    errorCode = "JOURNAL_DESTINATION_INVALID",
                )
            }
            try {
                val snapshot = journalMutex.withLock {
                    buildPersistedSnapshot()?.also { sessionStore.save(it) }
                        ?: sessionStore.loadLatest()
                } ?: return@withContext JournalExportResult(
                    sessionId = currentSessionId,
                    status = JournalExportStatus.FAILED,
                    summary = "Нет журнала для экспорта",
                    errorCode = "JOURNAL_EMPTY",
                )
                val payload = snapshot.toJson().toString(2)
                val bytes = payload.toByteArray(Charsets.UTF_8)
                if (bytes.size.toLong() > SessionStore.MAX_JOURNAL_BYTES) {
                    return@withContext JournalExportResult(
                        sessionId = snapshot.sessionId,
                        status = JournalExportStatus.FAILED,
                        summary = "Журнал превышает лимит экспорта",
                        errorCode = "JOURNAL_TOO_LARGE",
                    )
                }
                val output = appContext.contentResolver.openOutputStream(uri)
                    ?: return@withContext JournalExportResult(
                        sessionId = snapshot.sessionId,
                        status = JournalExportStatus.FAILED,
                        summary = "Не удалось открыть файл экспорта",
                        errorCode = "JOURNAL_EXPORT_OPEN_FAILED",
                    )
                output.use {
                    it.write(bytes)
                    it.flush()
                }
                val result = JournalExportResult(
                    sessionId = snapshot.sessionId,
                    status = JournalExportStatus.EXPORTED,
                    bytes = bytes.size.toLong(),
                    summary = "Redacted journal экспортирован",
                )
                if (!closed) {
                    append(
                        AgentEventKind.INFO,
                        "Redacted journal экспортирован",
                        "bytes=" + bytes.size,
                    )
                }
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val summary = AgentRedactor.text(
                    error.message ?: "Не удалось экспортировать journal",
                    MAX_ERROR_CHARS,
                ).orEmpty()
                if (!closed) {
                    append(AgentEventKind.ERROR, summary)
                }
                JournalExportResult(
                    sessionId = currentSessionId,
                    status = JournalExportStatus.UNKNOWN,
                    summary = summary,
                    errorCode = "JOURNAL_EXPORT_UNKNOWN",
                )
            }
        }

    override fun cancel() {
        activeJob?.cancel()
        providerRegistry.cancelAll()
        actionsClient.cancelActive()
        pullRequests.cancelActive()
        patchApplyJob?.cancel()
        patchApplyJob = null
        interactiveSession.cancelActive()
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
        val refreshed = workspaceManager.refresh(summary.id)
        refreshWorkspace()
        return refreshed.toAgentSnapshot().also { _workspace.value = it }
    }



    override suspend fun prepareImage(
        uri: String,
        displayName: String?,
    ): ImageAnalysisState {
        check(!closed) { "AgentCore уже закрыт" }
        val normalizedUri = uri.trim()
        require(normalizedUri.isNotBlank()) { "URI изображения не задан" }
        _imageState.value = ImageAnalysisState(
            status = ImageAnalysisStatus.VALIDATING,
            displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
            summary = "Проверка изображения",
        )
        return try {
            val asset = imagePipeline.prepare(
                resolver = appContext.contentResolver,
                uri = Uri.parse(normalizedUri),
                displayName = displayName,
            )
            val ready = asset.toState()
            _imageState.value = ready
            append(
                AgentEventKind.IMAGE,
                "Изображение подготовлено для визуального анализа",
                ready.toJson().toString(),
            )
            persistAsync()
            ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failed = ImageAnalysisState(
                status = ImageAnalysisStatus.FAILED,
                displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
                summary = AgentRedactor.text(
                    error.message ?: "Не удалось подготовить изображение",
                    MAX_ERROR_CHARS,
                ),
                errorCode = "IMAGE_PREPARE_FAILED",
            )
            _imageState.value = failed
            append(
                AgentEventKind.ERROR,
                failed.summary ?: "Не удалось подготовить изображение",
                failed.toJson().toString(),
            )
            persistAsync()
            failed
        }
    }

    override fun clearImage() {
        val assetId = _imageState.value.assetId
        imagePipeline.clear(assetId)
        _imageState.value = ImageAnalysisState(
            status = ImageAnalysisStatus.IDLE,
            summary = "Изображение удалено из временного cache",
        )
        append(
            AgentEventKind.IMAGE,
            "Временное изображение удалено пользователем",
        )
        persistAsync()
    }


    override suspend fun selectWorkspace(workspaceId: String): AgentWorkspaceSnapshot {
        check(!closed) { "AgentCore уже закрыт" }
        val cached = cachedGitResult(operationId)
        if (cached != null && cached.sessionId == currentSessionId) {
            return cached
        }
        if (_state.value.status == AgentSessionStatus.UNKNOWN) {
            val result = GitOperationResult(
                operation = operation,
                operationId = operationId,
                status = GitOperationStatus.UNKNOWN,
                summary = "Предыдущая операция неизвестна; сначала выполните re-check",
                errorCode = "GIT_RECHECK_REQUIRED",
            )
            val boundResult = result.copy(sessionId = currentSessionId)
            publishGitResult(boundResult)
            return boundResult
        }
        if (
            _state.value.status == AgentSessionStatus.RUNNING ||
            _state.value.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            throw IllegalStateException(
                "Нельзя менять workspace во время активной сессии или approval",
            )
        }
        val selected = workspaceManager.select(workspaceId)
        val refreshed = workspaceManager.refresh(selected.id)
        val catalog = refreshWorkspace()
        append(
            AgentEventKind.INFO,
            "Workspace выбран пользователем",
            "workspace_id=" + selected.id +
                "; fingerprint=" + (catalog.fingerprint ?: "unknown"),
        )
        return refreshed.toAgentSnapshot()
    }

    override suspend fun refreshWorkspace(): WorkspaceCatalogState {
        check(!closed) { "AgentCore уже закрыт" }
        _workspaceCatalog.value = _workspaceCatalog.value.copy(
            status = WorkspaceCatalogStatus.LOADING,
            summary = "Workspace catalog обновляется",
            errorCode = null,
            updatedAt = System.currentTimeMillis(),
        )
        return try {
            val selectedId = workspaceManager.current.value?.id
            val selected = selectedId?.let { workspaceManager.refresh(it) }
            val items = workspaceManager.list().map { it.toAgentSnapshot() }
            val tree = selectedId?.let {
                workspaceManager.treePage(
                    id = it,
                    maxDepth = 6,
                    maxEntries = 300,
                )
            } ?: dev.deepagent.mobile.agent.model.WorkspaceTreePage()
            val rulesResult = selectedId?.let {
                toolRouter.readProjectRules(it)
            }
            val rules = if (rulesResult?.ok == true) {
                WorkspaceRulesMetadata(
                    available = true,
                    sizeBytes = rulesResult.content.toByteArray(Charsets.UTF_8).size,
                    truncated = rulesResult.truncated,
                )
            } else {
                WorkspaceRulesMetadata()
            }
            val next = WorkspaceCatalogState(
                selectedId = selectedId,
                items = items,
                entries = tree.entries,
                entriesTruncated = tree.truncated,
                fingerprint = selected?.fingerprint,
                rules = rules,
                status = WorkspaceCatalogStatus.READY,
                summary = if (selectedId == null) {
                    "Workspace не выбран"
                } else {
                    "Workspace catalog готов"
                },
                errorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            _workspace.value = selected?.toAgentSnapshot()
            _workspaceCatalog.value = next
            next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failed = _workspaceCatalog.value.copy(
                status = WorkspaceCatalogStatus.FAILED,
                summary = AgentRedactor.text(
                    error.message ?: "Не удалось обновить workspace catalog",
                    MAX_ERROR_CHARS,
                ),
                errorCode = "WORKSPACE_CATALOG_FAILED",
                updatedAt = System.currentTimeMillis(),
            )
            _workspaceCatalog.value = failed
            failed
        }
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
        val operationId = normalizeOperationId(request.operationId)
        return runGitWrite(
            operation = GitOperation.CREATE_BRANCH,
            operationId = operationId,
            requiredPermission = PermissionMode.GITHUB_WRITE,
            description = "Создание branch",
        ) {
            toolRouter.createGitBranch(
                workspaceIdForActions(),
                request.copy(operationId = operationId),
            )
        }
    }

    override suspend fun commitGit(
        request: GitCommitRequest,
    ): GitOperationResult {
        val operationId = normalizeOperationId(request.operationId)
        return runGitWrite(
            operation = GitOperation.COMMIT,
            operationId = operationId,
            requiredPermission = PermissionMode.GITHUB_WRITE,
            description = "Создание commit",
        ) {
            toolRouter.commitGit(
                workspaceIdForActions(),
                request.copy(operationId = operationId),
            )
        }
    }

    override suspend fun pushGit(
        request: GitPushRequest,
    ): GitOperationResult {
        val operationId = normalizeOperationId(request.operationId)
        return runGitWrite(
            operation = GitOperation.PUSH,
            operationId = operationId,
            requiredPermission = PermissionMode.GITHUB_WRITE,
            description = "Push branch",
        ) {
            toolRouter.pushGit(
                workspaceIdForActions(),
                request.copy(operationId = operationId),
            )
        }
    }

    override suspend fun createPullRequest(
        request: GitPullRequestRequest,
        githubToken: String,
    ): GitOperationResult {
        val explicitToken = githubToken.trim()
        val token = if (explicitToken.isNotBlank()) {
            explicitToken
        } else {
            readCredential(CredentialKind.GITHUB_TOKEN).orEmpty()
        }
        val operationId = normalizeOperationId(request.operationId)
        return runGitWrite(
            operation = GitOperation.CREATE_PULL_REQUEST,
            operationId = operationId,
            requiredPermission = PermissionMode.PR_CREATE,
            description = "Создание Pull Request",
        ) {
            val sessionId = currentSessionId?.trim()
            val requestedSessionId = request.sessionId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val requestedExpectedSha = request.expectedHeadSha
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            when {
                sessionId.isNullOrBlank() -> GitOperationResult(
                    operation = GitOperation.CREATE_PULL_REQUEST,
                    sessionId = sessionId,
                    repository = request.repository,
                    base = request.base,
                    status = GitOperationStatus.FAILED,
                    summary = "Для PR нужна активная session ID",
                    errorCode = "SESSION_REQUIRED",
                )

                requestedSessionId != null && requestedSessionId != sessionId -> GitOperationResult(
                    operation = GitOperation.CREATE_PULL_REQUEST,
                    sessionId = sessionId,
                    repository = request.repository,
                    base = request.base,
                    status = GitOperationStatus.FAILED,
                    summary = "PR session ID не совпадает с текущей сессией",
                    errorCode = "SESSION_MISMATCH",
                )

                token.isBlank() -> GitOperationResult(
                    operation = GitOperation.CREATE_PULL_REQUEST,
                    sessionId = sessionId,
                    repository = request.repository,
                    base = request.base,
                    status = GitOperationStatus.FAILED,
                    summary = "GitHub token не задан",
                    errorCode = "GITHUB_TOKEN_MISSING",
                )

                else -> {
                    val preflight = toolRouter.inspectGit(workspaceIdForActions())
                    val currentHeadSha = preflight.headSha?.trim()
                    when {
                        preflight.status == GitOperationStatus.UNKNOWN -> GitOperationResult(
                            operation = GitOperation.CREATE_PULL_REQUEST,
                            sessionId = sessionId,
                            repository = request.repository,
                            base = request.base,
                            status = GitOperationStatus.UNKNOWN,
                            summary = "Текущий Git HEAD не подтверждён; PR остановлен",
                            errorCode = "GIT_HEAD_UNKNOWN",
                        )

                        preflight.status != GitOperationStatus.SUCCEEDED ||
                            !SHA_PATTERN.matches(currentHeadSha.orEmpty()) -> GitOperationResult(
                            operation = GitOperation.CREATE_PULL_REQUEST,
                            sessionId = sessionId,
                            repository = request.repository,
                            base = request.base,
                            status = GitOperationStatus.FAILED,
                            summary = "Для PR нужен подтверждённый текущий Git HEAD",
                            errorCode = "GIT_HEAD_REQUIRED",
                        )

                        requestedExpectedSha != null &&
                            !requestedExpectedSha.equals(
                                currentHeadSha,
                                ignoreCase = true,
                            ) -> GitOperationResult(
                            operation = GitOperation.CREATE_PULL_REQUEST,
                            sessionId = sessionId,
                            repository = request.repository,
                            base = request.base,
                            expectedHeadSha = currentHeadSha,
                            headSha = currentHeadSha,
                            status = GitOperationStatus.FAILED,
                            summary = "Git HEAD изменился; сначала выполните re-check",
                            errorCode = "GIT_HEAD_CHANGED_RECHECK_REQUIRED",
                        )

                        else -> {
                            val boundRequest = request.copy(
                                expectedHeadSha = currentHeadSha,
                                sessionId = sessionId,
                                operationId = operationId,
                            )
                            when (val result = pullRequests.create(boundRequest, token)) {
                                is GitHubPullRequestResult.Created -> GitOperationResult(
                                    operation = GitOperation.CREATE_PULL_REQUEST,
                                    sessionId = boundRequest.sessionId,
                                    repository = boundRequest.repository,
                                    base = boundRequest.base,
                                    expectedHeadSha = boundRequest.expectedHeadSha,
                                    status = GitOperationStatus.SUCCEEDED,
                                    summary = "Pull Request создан: #" + result.number,
                                    branch = result.head,
                                    headSha = result.headSha,
                                    pullRequestNumber = result.number,
                                    pullRequestUrl = result.url,
                                )

                                is GitHubPullRequestResult.Failed -> GitOperationResult(
                                    operation = GitOperation.CREATE_PULL_REQUEST,
                                    sessionId = boundRequest.sessionId,
                                    repository = boundRequest.repository,
                                    base = boundRequest.base,
                                    expectedHeadSha = boundRequest.expectedHeadSha,
                                    headSha = currentHeadSha,
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
                }
            }
        }
    }


    override suspend fun runInteractive(
        request: InteractiveCommandRequest,
    ): InteractiveSessionState {
        check(!closed) { "AgentCore уже закрыт" }
        if (
            _state.value.status == AgentSessionStatus.RUNNING ||
            _state.value.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            return interactiveFailure(
                request,
                "Сначала завершите текущую сессию Agent Core",
                "SESSION_BUSY",
            )
        }
        if (
            _interactiveState.value.status == InteractiveSessionStatus.STARTING ||
            _interactiveState.value.status == InteractiveSessionStatus.RUNNING
        ) {
            return _interactiveState.value
        }
        val runtime = runtimeSupervisor.probe(currentSessionId)
        _runtimeState.value = runtime
        if (runtime.status != RuntimeStatus.READY) {
            return interactiveFailure(
                request,
                "Interactive command требует READY RuntimeSupervisor",
                "RUNTIME_NOT_READY",
            )
        }
        val requiredPermission = interactiveSession.requiredPermission(request)
        val permission = currentRequestSummary?.permission ?: PermissionMode.READ_ONLY
        if (!permission.allows(requiredPermission)) {
            recordDecision(
                kind = "INTERACTIVE",
                state = "DENIED",
                detail = "required=" + requiredPermission.name,
            )
            return interactiveFailure(
                request,
                "Для этой interactive command нужен permission " +
                    requiredPermission.name,
                "PERMISSION_REQUIRED",
            )
        }
        val sessionId = request.sessionId ?: currentSessionId
            ?: UUID.randomUUID().toString()
        recordDecision(
            kind = "INTERACTIVE",
            state = "APPROVED",
            detail = "user_action=true",
        )
        append(
            AgentEventKind.APPROVAL,
            "Пользователь подтвердил interactive command",
            request.copy(sessionId = sessionId).toAuditJson().toString(),
        )
        val initial = InteractiveSessionState(
            sessionId = sessionId,
            workspaceId = request.workspaceId,
            executable = request.executable,
            args = request.args.take(32),
            cwd = request.cwd,
            status = InteractiveSessionStatus.STARTING,
            summary = "Interactive command запускается",
        )
        _interactiveState.value = initial
        persistAsync()
        val result = interactiveSession.run(
            request.copy(sessionId = sessionId),
        ) { update ->
            _interactiveState.value = update
            persistAsync()
        }
        _interactiveState.value = result
        append(
            if (result.status == InteractiveSessionStatus.SUCCEEDED) {
                AgentEventKind.TOOL
            } else {
                AgentEventKind.ERROR
            },
            result.summary ?: "Interactive command обновил состояние",
            result.toJson().toString(),
        )
        persistAsync()
        if (result.status == InteractiveSessionStatus.UNKNOWN) {
            markUnknown(
                (result.summary ?: "Interactive process state неизвестен") +
                    "; автоматическое восстановление запрещено",
            )
        }
        return result
    }

    override fun sendInteractiveInput(
        sessionId: String,
        input: String,
    ): Boolean {
        val sent = interactiveSession.sendInput(sessionId, input)
        if (sent) {
            append(
                AgentEventKind.TOOL,
                "Interactive input передан",
                "session_id=" + sessionId.take(160),
            )
        }
        return sent
    }

    override fun cancelInteractive() {
        val sessionId = _interactiveState.value.sessionId
        if (interactiveSession.cancelActive(sessionId)) {
            _interactiveState.value = _interactiveState.value.copy(
                status = InteractiveSessionStatus.CANCELLED,
                summary = "Interactive process остановлен пользователем",
                errorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            append(
                AgentEventKind.INFO,
                "Interactive process остановлен пользователем",
                "session_id=" + (sessionId ?: "unknown"),
            )
            persistAsync()
        }
    }

    private fun interactiveFailure(
        request: InteractiveCommandRequest,
        summary: String,
        errorCode: String,
    ): InteractiveSessionState {
        val result = InteractiveSessionState(
            sessionId = request.sessionId ?: currentSessionId,
            workspaceId = request.workspaceId,
            executable = request.executable,
            args = request.args.take(32),
            cwd = request.cwd,
            status = InteractiveSessionStatus.FAILED,
            summary = summary,
            errorCode = errorCode,
        )
        _interactiveState.value = result
        append(AgentEventKind.ERROR, summary, result.toJson().toString())
        return result
    }



    override suspend fun startRuntime(requestedPermission: PermissionMode): RuntimeState {
        check(!closed) { "AgentCore уже закрыт" }
        if (
            _state.value.status == AgentSessionStatus.RUNNING ||
            _state.value.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            val busy = RuntimeState(
                status = RuntimeStatus.FAILED,
                sessionId = currentSessionId,
                summary = "Сначала завершите текущую сессию Agent Core",
                errorCode = "SESSION_BUSY",
            )
            _runtimeState.value = busy
            append(AgentEventKind.ERROR, busy.summary.orEmpty(), busy.toJson().toString())
            return busy
        }
        val permission = requestedPermission
        if (!permission.allows(PermissionMode.LOCAL_WRITE)) {
            val denied = RuntimeState(
                status = RuntimeStatus.FAILED,
                sessionId = currentSessionId,
                summary = "Для установки runtime нужен permission LOCAL_WRITE",
                errorCode = "PERMISSION_REQUIRED",
            )
            _runtimeState.value = denied
            recordDecision(
                kind = "RUNTIME",
                state = "DENIED",
                detail = "required=" + PermissionMode.LOCAL_WRITE.name,
            )
            append(AgentEventKind.ERROR, denied.summary.orEmpty(), denied.toJson().toString())
            return denied
        }
        recordDecision(
            kind = "RUNTIME",
            state = "APPROVED",
            detail = "runtime start; user_action=true",
        )
        append(
            AgentEventKind.APPROVAL,
            "Пользователь подтвердил запуск runtime",
        )
        val result = runtimeSupervisor.start(currentSessionId)
        _runtimeState.value = result
        append(
            if (result.status == RuntimeStatus.READY) {
                AgentEventKind.BUILD
            } else {
                AgentEventKind.ERROR
            },
            result.summary ?: "Runtime обновил состояние",
            result.toJson().toString(),
        )
        return result
    }

    override suspend fun stopRuntime(): RuntimeState {
        check(!closed) { "AgentCore уже закрыт" }
        val result = runtimeSupervisor.stop(currentSessionId)
        _runtimeState.value = result
        append(
            if (result.status == RuntimeStatus.EMPTY) {
                AgentEventKind.TOOL
            } else {
                AgentEventKind.ERROR
            },
            result.summary ?: "Runtime обновил состояние",
            result.toJson().toString(),
        )
        return result
    }



    override suspend fun runActions(
        request: ActionsRunRequest,
    ): ActionsOperationState {
        externalMutationMutex.lock()
        try {
            check(!closed) { "AgentCore уже закрыт" }
            val boundRequest = request.copy(
                sessionId = request.sessionId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: currentSessionId,
                operationId = request.operationId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString(),
            )
            val operationId = boundRequest.operationId.orEmpty()
            val cached = cachedActionsResult(operationId)
            if (cached != null && cached.sessionId == boundRequest.sessionId) {
                _actionsState.value = cached
                append(
                    AgentEventKind.INFO,
                    "Повтор Actions operation возвращает ранее подтверждённый результат",
                    cached.toJson().toString(),
                )
                return cached
            }
            if (
                _actionsState.value.status == ActionsOperationStatus.UNKNOWN &&
                _actionsState.value.sessionId == boundRequest.sessionId
            ) {
                val blocked = ActionsOperationState(
                    sessionId = boundRequest.sessionId,
                    operationId = operationId,
                    repository = boundRequest.repository,
                    workflow = boundRequest.workflow,
                    ref = boundRequest.ref,
                    status = ActionsOperationStatus.UNKNOWN,
                    summary = "Предыдущая Actions-операция не подтверждена; сначала выполните re-check",
                    errorCode = "ACTIONS_RECHECK_REQUIRED",
                )
                _actionsState.value = blocked
                append(
                    AgentEventKind.ERROR,
                    blocked.summary.orEmpty(),
                    blocked.toJson().toString(),
                )
                return blocked
            }
            val result = runActionsLocked(boundRequest)
            if (
                result.status == ActionsOperationStatus.SUCCEEDED ||
                result.status == ActionsOperationStatus.UNKNOWN
            ) {
                cacheActionsResult(result)
            }
            return result
        } finally {
            externalMutationMutex.unlock()
        }
    }

    private suspend fun runActionsLocked(
        request: ActionsRunRequest,
    ): ActionsOperationState {
        check(!closed) { "AgentCore уже закрыт" }
        val boundRequest = request.copy(
            sessionId = request.sessionId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: currentSessionId,
            operationId = request.operationId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString(),
        )
        if (
            _state.value.status == AgentSessionStatus.RUNNING ||
            _state.value.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            val busy = ActionsOperationState(
                sessionId = boundRequest.sessionId,
                operationId = boundRequest.operationId,
                repository = request.repository,
                workflow = request.workflow,
                ref = request.ref,
                status = ActionsOperationStatus.FAILED,
                summary = "Сначала завершите текущую сессию Agent Core",
                errorCode = "SESSION_BUSY",
            )
            _actionsState.value = busy
            append(AgentEventKind.ERROR, busy.summary.orEmpty(), busy.toJson().toString())
            return busy
        }
        val permission = currentRequestSummary?.permission ?: PermissionMode.READ_ONLY
        if (!permission.allows(PermissionMode.GITHUB_WRITE)) {
            val denied = ActionsOperationState(
                sessionId = boundRequest.sessionId,
                operationId = boundRequest.operationId,
                repository = request.repository,
                workflow = request.workflow,
                ref = request.ref,
                status = ActionsOperationStatus.FAILED,
                summary = "Для workflow dispatch нужен permission GITHUB_WRITE",
                errorCode = "PERMISSION_REQUIRED",
            )
            _actionsState.value = denied
            recordDecision(
                kind = "ACTIONS",
                state = "DENIED",
                detail = "operation_id=" + boundRequest.operationId +
                    "; required=" + PermissionMode.GITHUB_WRITE.name,
            )
            append(AgentEventKind.ERROR, denied.summary.orEmpty(), denied.toJson().toString())
            return denied
        }

        recordDecision(
            kind = "ACTIONS",
            state = "APPROVED",
            detail = "operation_id=" + boundRequest.operationId +
                "; workflow dispatch; user_action=true",
        )
        append(
            AgentEventKind.APPROVAL,
            "Пользователь подтвердил запуск GitHub Actions",
            boundRequest.toAuditJson().toString(),
        )
        val actionToken = request.token.trim().takeIf { it.isNotBlank() }
            ?: readCredential(CredentialKind.GITHUB_TOKEN).orEmpty()
        val result = runActionsInternal(
            boundRequest.copy(
                token = actionToken,
            ),
        )
        when (result.status) {
            ActionsOperationStatus.UNKNOWN -> markUnknown(
                (result.summary ?: "Actions завершился без подтверждённого результата") +
                    "; повтор запрещён до re-check",
            )
            ActionsOperationStatus.FAILED -> fail(
                result.summary ?: "Actions завершился с ошибкой",
            )
            else -> Unit
        }
        return result
    }

    override suspend fun saveVerifiedArtifact(
        request: ActionsArtifactRequest,
    ): ActionsArtifactSaveResult {
        check(!closed) { "AgentCore уже закрыт" }
        if (
            _state.value.status == AgentSessionStatus.RUNNING ||
            _state.value.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            return artifactSaveFailure(
                request,
                "Сначала завершите текущую сессию Agent Core",
                "SESSION_BUSY",
            )
        }
        val actionState = _actionsState.value
        if (
            actionState.status != ActionsOperationStatus.SUCCEEDED ||
            actionState.runId != request.runId ||
            actionState.headSha.isNullOrBlank() ||
            !actionState.headSha.equals(request.expectedCommitSha, ignoreCase = true)
        ) {
            return artifactSaveFailure(
                request,
                "Artifact не связан с последним подтверждённым Actions run",
                "ARTIFACT_PROVENANCE_REQUIRED",
            )
        }
        val permission = currentRequestSummary?.permission ?: PermissionMode.READ_ONLY
        if (!permission.allows(PermissionMode.LOCAL_WRITE)) {
            recordDecision(
                kind = "ARTIFACT",
                state = "DENIED",
                detail = "required=" + PermissionMode.LOCAL_WRITE.name,
            )
            return artifactSaveFailure(
                request,
                "Для сохранения artifact нужен permission LOCAL_WRITE",
                "PERMISSION_REQUIRED",
            )
        }
        val workspaceId = request.workspaceId ?: workspaceManager.current.value?.id
        val root = workspaceManager.resolveRoot(workspaceId)
            ?: return artifactSaveFailure(
                request,
                "Workspace не выбран или недоступен",
                "WORKSPACE_UNAVAILABLE",
            )
        if (
            currentRequestSummary?.workspaceId != null &&
            currentRequestSummary?.workspaceId != workspaceId
        ) {
            return artifactSaveFailure(
                request,
                "Artifact workspace не совпадает с текущей сессией",
                "WORKSPACE_MISMATCH",
            )
        }
        val before = workspaceManager.captureIdentity(workspaceId)?.treeSha256
        if (before == null) {
            return artifactSaveFailure(
                request,
                "Workspace fingerprint перед сохранением недоступен",
                "WORKSPACE_RECHECK_REQUIRED",
            )
        }

        recordDecision(
            kind = "ARTIFACT",
            state = "APPROVED",
            detail = "artifact_id=" + request.artifactId,
        )
        append(
            AgentEventKind.APPROVAL,
            "Пользователь подтвердил сохранение проверенного artifact",
            "artifact_id=" + request.artifactId,
        )

        val artifactToken = request.token.trim().takeIf { it.isNotBlank() }
            ?: readCredential(CredentialKind.GITHUB_TOKEN).orEmpty()
        val download = actionsClient.downloadAndVerifyArtifact(
            request.copy(token = artifactToken),
        )
        val result = when (download) {
            is ArtifactDownloadResult.Verified -> {
                val saved = artifactManager.save(
                    workspaceRoot = root,
                    artifact = download.artifact,
                    requestedName = request.outputName,
                )
                saved.copy(
                    sessionId = currentSessionId,
                    workspaceId = workspaceId,
                    workspaceFingerprintBefore = before,
                    workspaceFingerprintAfter =
                        workspaceManager.captureIdentity(workspaceId)?.treeSha256,
                )
            }
            is ArtifactDownloadResult.Failed -> ActionsArtifactSaveResult(
                sessionId = currentSessionId,
                workspaceId = workspaceId,
                artifactId = request.artifactId,
                status = ActionsArtifactSaveStatus.FAILED,
                summary = download.message,
                errorCode = download.errorCode,
            )
            is ArtifactDownloadResult.Unknown -> ActionsArtifactSaveResult(
                sessionId = currentSessionId,
                workspaceId = workspaceId,
                artifactId = request.artifactId,
                status = ActionsArtifactSaveStatus.UNKNOWN,
                summary = download.message,
                errorCode = download.errorCode,
            )
        }

        val finalResult = if (
            result.status == ActionsArtifactSaveStatus.VERIFIED_SAVED &&
            result.workspaceFingerprintAfter == null
        ) {
            result.copy(
                status = ActionsArtifactSaveStatus.UNKNOWN,
                summary = "Artifact сохранён, но fingerprint после записи не подтверждён",
                errorCode = "ARTIFACT_SAVE_RECHECK_REQUIRED",
            )
        } else {
            result
        }
        if (finalResult.status == ActionsArtifactSaveStatus.VERIFIED_SAVED) {
            _actionsState.value = _actionsState.value.copy(
                artifacts = _actionsState.value.artifacts.map { artifact ->
                    if (artifact.id == request.artifactId) {
                        artifact.copy(
                            verified = true,
                            sourceSha = finalResult.sourceSha,
                            checksum = finalResult.checksum,
                            savedPath = finalResult.relativePath,
                        )
                    } else {
                        artifact
                    }
                },
                updatedAt = System.currentTimeMillis(),
            )
            append(
                AgentEventKind.ARTIFACT,
                finalResult.summary,
                finalResult.toJson().toString(),
            )
        } else {
            append(
                AgentEventKind.ERROR,
                finalResult.summary,
                finalResult.toJson().toString(),
            )
            if (finalResult.status == ActionsArtifactSaveStatus.UNKNOWN) {
                markUnknown(
                    finalResult.summary +
                        "; повторное скачивание запрещено до re-check",
                )
            }
        }
        persistAsync()
        return finalResult
    }

    private suspend fun runActionsInternal(
        request: ActionsRunRequest,
    ): ActionsOperationState {
        val sessionId = request.sessionId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: currentSessionId
        val operationId = request.operationId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val correlationError = when {
            sessionId.isNullOrBlank() -> "Для Actions нужен обязательный agent_session_id"
            !SESSION_ID_PATTERN.matches(sessionId.orEmpty()) ->
                "Session correlation id имеет недопустимый формат"
            !OPERATION_ID_PATTERN.matches(operationId) ->
                "Operation correlation id имеет недопустимый формат"
            else -> null
        }
        if (correlationError != null) {
            val rejected = ActionsOperationState(
                sessionId = sessionId,
                operationId = operationId,
                repository = request.repository.trim(),
                workflow = request.workflow.trim(),
                ref = request.ref.trim(),
                status = ActionsOperationStatus.FAILED,
                summary = correlationError,
                errorCode = when {
                    sessionId.isNullOrBlank() -> "ACTIONS_SESSION_ID_REQUIRED"
                    !SESSION_ID_PATTERN.matches(sessionId.orEmpty()) ->
                        "ACTIONS_SESSION_ID_INVALID"
                    else -> "ACTIONS_OPERATION_ID_INVALID"
                },
            )
            _actionsState.value = rejected
            append(AgentEventKind.ERROR, rejected.summary.orEmpty(), rejected.toJson().toString())
            return rejected
        }
        val workspaceId = currentRequestSummary?.workspaceId
            ?: workspaceManager.current.value?.id
        val suppliedSha = request.expectedCommitSha
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (suppliedSha != null && !SHA_PATTERN.matches(suppliedSha)) {
            val rejected = ActionsOperationState(
                sessionId = sessionId,
                operationId = operationId,
                repository = request.repository.trim(),
                workflow = request.workflow.trim(),
                ref = request.ref.trim(),
                status = ActionsOperationStatus.FAILED,
                summary = "Ожидаемый commit SHA имеет недопустимый формат",
                errorCode = "ACTIONS_SOURCE_SHA_INVALID",
            )
            _actionsState.value = rejected
            append(AgentEventKind.ERROR, rejected.summary.orEmpty(), rejected.toJson().toString())
            return rejected
        }
        val currentHeadSha = toolRouter.currentGitHeadSha(workspaceId)
        if (currentHeadSha == null || !SHA_PATTERN.matches(currentHeadSha)) {
            val rejected = ActionsOperationState(
                sessionId = sessionId,
                operationId = operationId,
                repository = request.repository.trim(),
                workflow = request.workflow.trim(),
                ref = request.ref.trim(),
                status = ActionsOperationStatus.FAILED,
                summary = "Не удалось получить ожидаемый Git HEAD SHA; Actions не запущен",
                errorCode = "ACTIONS_SOURCE_SHA_REQUIRED",
            )
            _actionsState.value = rejected
            append(AgentEventKind.ERROR, rejected.summary.orEmpty(), rejected.toJson().toString())
            return rejected
        }
        if (
            suppliedSha != null &&
            !suppliedSha.equals(currentHeadSha, ignoreCase = true)
        ) {
            val rejected = ActionsOperationState(
                sessionId = sessionId,
                operationId = operationId,
                repository = request.repository.trim(),
                workflow = request.workflow.trim(),
                ref = request.ref.trim(),
                status = ActionsOperationStatus.FAILED,
                summary = "Git HEAD изменился относительно ожидаемого SHA; сначала выполните re-check",
                errorCode = "ACTIONS_SOURCE_SHA_MISMATCH",
            )
            _actionsState.value = rejected
            append(AgentEventKind.ERROR, rejected.summary.orEmpty(), rejected.toJson().toString())
            return rejected
        }
        val expectedCommitSha = currentHeadSha
        val effectiveRequest = request.copy(
            sessionId = sessionId,
            operationId = operationId,
            expectedCommitSha = expectedCommitSha,
        )
        val initial = ActionsOperationState(
            sessionId = sessionId,
            operationId = operationId,
            repository = effectiveRequest.repository.trim(),
            workflow = effectiveRequest.workflow.trim(),
            ref = effectiveRequest.ref.trim(),
            status = ActionsOperationStatus.DISPATCHING,
            summary = "GitHub Actions запускается",
        )
        _actionsState.value = initial
        persistAsync()
        append(
            AgentEventKind.BUILD,
            "GitHub Actions workflow dispatch",
            effectiveRequest.toAuditJson().toString(),
        )
        val result = actionsClient.observe(
            effectiveRequest,
        ) { update ->
            _actionsState.value = update.copy(
                sessionId = sessionId,
                operationId = operationId,
            )
            persistAsync()
        }.copy(
            sessionId = sessionId,
            operationId = operationId,
        )
        _actionsState.value = result
        append(
            if (result.status == ActionsOperationStatus.SUCCEEDED) {
                AgentEventKind.BUILD
            } else {
                AgentEventKind.ERROR
            },
            result.summary ?: "GitHub Actions обновил состояние",
            result.toJson().toString(),
        )
        persistAsync()
        return result
    }

    private fun artifactSaveFailure(
        request: ActionsArtifactRequest,
        summary: String,
        errorCode: String,
    ): ActionsArtifactSaveResult {
        val result = ActionsArtifactSaveResult(
            sessionId = currentSessionId,
            workspaceId = request.workspaceId,
            artifactId = request.artifactId,
            status = ActionsArtifactSaveStatus.FAILED,
            summary = summary,
            errorCode = errorCode,
        )
        append(AgentEventKind.ERROR, summary, result.toJson().toString())
        return result
    }



    override suspend fun rollbackLastPatch(): PatchRollbackResult {
        check(!closed) { "AgentCore уже закрыт" }

        val recovery = _patchRecovery.value
            ?: return recordPatchRollbackFailure(
                operationId = null,
                summary = "Нет подтверждённого patch checkpoint для rollback",
                errorCode = "PATCH_CHECKPOINT_NOT_FOUND",
            )
        if (recovery.status != PatchRecoveryStatus.APPLIED) {
            return recordPatchRollbackFailure(
                operationId = recovery.operationId,
                path = recovery.path,
                summary = "Rollback недоступен: checkpoint требует re-check",
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }
        if (
            _state.value.status == AgentSessionStatus.RUNNING ||
            _state.value.status == AgentSessionStatus.WAITING_APPROVAL
        ) {
            return recordPatchRollbackFailure(
                operationId = recovery.operationId,
                path = recovery.path,
                summary = "Сначала завершите текущую сессию или pending approval",
                errorCode = "SESSION_BUSY",
            )
        }
        if (workspaceManager.current.value?.id != recovery.workspaceId) {
            return recordPatchRollbackFailure(
                operationId = recovery.operationId,
                path = recovery.path,
                summary = "Выберите workspace, в котором был применён patch",
                errorCode = "WORKSPACE_NOT_SELECTED",
            )
        }

        val expectedFingerprint = recovery.workspaceFingerprintAfter
        if (expectedFingerprint.isNullOrBlank()) {
            _patchRecovery.value = recovery.copy(
                status = PatchRecoveryStatus.UNKNOWN,
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
                updatedAt = System.currentTimeMillis(),
            )
            return recordPatchRollbackFailure(
                operationId = recovery.operationId,
                path = recovery.path,
                summary = "Post-write fingerprint отсутствует; rollback остановлен",
                errorCode = "PATCH_ROLLBACK_RECHECK_REQUIRED",
            )
        }

        val permission = currentRequestSummary?.permission ?: PermissionMode.READ_ONLY
        if (!permission.allows(PermissionMode.LOCAL_WRITE)) {
            recordDecision(
                kind = "PATCH",
                state = "DENIED",
                detail = "ROLLBACK; required=" + PermissionMode.LOCAL_WRITE.name,
            )
            return recordPatchRollbackFailure(
                operationId = recovery.operationId,
                path = recovery.path,
                summary = "Для rollback нужен permission LOCAL_WRITE",
                errorCode = "PERMISSION_REQUIRED",
            )
        }

        recordDecision(
            kind = "PATCH",
            state = "APPROVED",
            detail = "ROLLBACK; operation_id=" + recovery.operationId,
        )
        append(
            AgentEventKind.APPROVAL,
            "Пользователь подтвердил rollback patch",
            "operation_id=" + recovery.operationId,
        )

        val result = try {
            toolRouter.rollbackPatch(
                workspaceId = recovery.workspaceId,
                operationId = recovery.operationId,
                expectedWorkspaceFingerprint = expectedFingerprint,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PatchRollbackResult(
                operationId = recovery.operationId,
                path = recovery.path,
                status = PatchRollbackStatus.UNKNOWN,
                summary = AgentRedactor.text(
                    error.message ?: "Rollback завершился без подтверждения",
                    MAX_ERROR_CHARS,
                ).orEmpty(),
                errorCode = "PATCH_ROLLBACK_UNKNOWN",
            )
        }

        when (result.status) {
            PatchRollbackStatus.SUCCEEDED -> {
                _patchRecovery.value = recovery.copy(
                    status = PatchRecoveryStatus.ROLLED_BACK,
                    workspaceFingerprintAfter =
                        result.workspaceFingerprintAfter
                            ?: recovery.workspaceFingerprintAfter,
                    errorCode = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            PatchRollbackStatus.UNKNOWN -> {
                _patchRecovery.value = recovery.copy(
                    status = PatchRecoveryStatus.UNKNOWN,
                    errorCode = result.errorCode ?: "PATCH_ROLLBACK_UNKNOWN",
                    updatedAt = System.currentTimeMillis(),
                )
                recoveryReason = AgentRedactor.text(
                    result.summary,
                    MAX_ERROR_CHARS,
                )
                _state.value = _state.value.copy(
                    status = AgentSessionStatus.UNKNOWN,
                    lastError = recoveryReason,
                    finishedAt = System.currentTimeMillis(),
                    recoveryRequired = true,
                )
            }

            PatchRollbackStatus.FAILED -> {
                _patchRecovery.value = recovery.copy(
                    errorCode = result.errorCode,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        val eventKind = if (result.status == PatchRollbackStatus.SUCCEEDED) {
            AgentEventKind.TOOL
        } else {
            AgentEventKind.ERROR
        }
        append(
            eventKind,
            result.summary,
            result.toJson().toString(),
        )
        return result
    }

    override fun close() {
        if (closed) return
        activeJob?.cancel()
        actionsClient.cancelActive()
        pullRequests.cancelActive()
        patchApplyJob?.cancel()
        patchApplyJob = null
        providerRegistry.cancelAll()
        interactiveSession.close()
        runtimeSupervisor.close()
        imagePipeline.clear()
        _credentialState.value = credentialVault.clearAll()
        coreScope.cancel()
        closed = true
        persistOnClose()
        lastGitResult = null
        synchronized(gitOperationCache) {
            gitOperationCache.clear()
        }
        synchronized(actionsOperationCache) {
            actionsOperationCache.clear()
        }
    }

    private suspend fun execute(request: AgentRequest) {
        val task = request.task.trim()
        if (task.isBlank()) {
            fail("Задача не может быть пустой")
            return
        }

        val target = resolveTarget(request)
        val workspaceId = request.workspaceId ?: workspaceManager.current.value?.id
        val sessionId = request.sessionId?.trim()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        currentSessionId = sessionId
        lastGitResult = null
        synchronized(gitOperationCache) {
            gitOperationCache.clear()
        }
        synchronized(actionsOperationCache) {
            actionsOperationCache.clear()
        }
        _gitState.value = GitOperationState(sessionId = sessionId)
        _actionsState.value = ActionsOperationState(sessionId = sessionId)
        _interactiveState.value = InteractiveSessionState(sessionId = sessionId)
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
            providerId = request.providerId.trim().takeIf { it.isNotBlank() },
            workspaceFingerprint = workspaceId?.let {
                workspaceManager.captureIdentity(it)?.treeSha256
            },
            repository = request.repository,
            workflow = request.workflow,
            ref = request.ref,
            deepSeekBaseUrl = request.deepSeekBaseUrl,
            model = request.model,
            imageAssetId = request.imageAssetId,
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
        val boundedPlan = BoundedPlanner.create(
            target = target,
            hasWorkspace = workspaceId != null,
            maxToolRounds = MAX_TOOL_ROUNDS,
        )
        append(
            AgentEventKind.PLAN,
            "Исполнитель: " + target.label(),
            "permission=" + request.permission.name +
                "; workspace=" + (workspaceId ?: "не выбран"),
        )
        append(
            AgentEventKind.PLAN,
            "Bounded plan сформирован",
            "steps=" + boundedPlan.steps.size +
                "; max_tool_rounds=" + boundedPlan.maxToolRounds,
            payload = boundedPlan.toJson().toString(),
        )

        val boundRequest = request.copy(
            task = task,
            workspaceId = workspaceId,
            sessionId = sessionId,
            deepSeekApiKey = null,
            githubToken = null,
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

    private fun validateRequest(request: AgentRequest) {
        val task = request.task.trim()
        require(task.isNotBlank()) { "Задача не может быть пустой" }
        require(task.length <= SessionRequestSummary.MAX_TASK_CHARS) {
            "Задача превышает лимит " + SessionRequestSummary.MAX_TASK_CHARS + " символов"
        }
        request.sessionId?.let { sessionId ->
            require(SESSION_ID_PATTERN.matches(sessionId.trim())) {
                "sessionId имеет недопустимый формат"
            }
        }
        require(request.image == null) {
            "Raw image attachment отключён; используйте AgentBridge.prepareImage"
        }
        validateDeepSeekBaseUrl(request.deepSeekBaseUrl)
    }

    private fun validateDeepSeekBaseUrl(value: String) {
        val normalized = value.trim()
        require(
            normalized.isNotBlank() &&
                normalized.length <= MAX_BASE_URL_CHARS &&
                normalized.none { it.isWhitespace() }
        ) {
            "DeepSeek base URL пустой или имеет недопустимый формат"
        }
        val url = runCatching { URL(normalized) }.getOrElse {
            throw IllegalArgumentException("DeepSeek base URL имеет неверный формат")
        }
        require(
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.equals(DEEPSEEK_API_HOST, ignoreCase = true) &&
                (url.port == -1 || url.port == 443) &&
                url.userInfo == null &&
                url.query == null &&
                url.ref == null
        ) {
            "DeepSeek base URL должен использовать HTTPS и разрешённый host"
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

        if (!request.permission.allows(PermissionMode.GITHUB_WRITE)) {
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

        val result = runActionsInternal(
            ActionsRunRequest(
                token = readCredential(CredentialKind.GITHUB_TOKEN).orEmpty(),
                repository = request.repository.orEmpty(),
                workflow = request.workflow.orEmpty(),
                ref = request.ref,
                sessionId = currentSessionId,
            ),
        )
        when (result.status) {
            ActionsOperationStatus.SUCCEEDED -> Unit
            ActionsOperationStatus.FAILED -> fail(
                result.summary ?: "GitHub Actions завершился с ошибкой",
            )
            ActionsOperationStatus.UNKNOWN -> markUnknown(
                (result.summary ?: "GitHub Actions завершился без подтверждения") +
                    "; повтор запрещён до re-check",
            )
            else -> markUnknown(
                "GitHub Actions не вернул конечное состояние; требуется re-check",
            )
        }
    }

    private suspend fun runDeepSeekAgent(request: AgentRequest) {
        val providerId = request.providerId.trim()
        val provider = providerRegistry.resolve(providerId)
        if (provider == null) {
            fail("Provider не зарегистрирован: " + providerId)
            return
        }

        val apiKey = readCredential(CredentialKind.DEEPSEEK_API_KEY).orEmpty()
        if (apiKey.isBlank()) {
            fail("DeepSeek API key не задан; выполнение остановлено")
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


        val imageAssetId = request.imageAssetId?.trim().orEmpty()
        val visualImage = if (imageAssetId.isNotBlank()) {
            val resolved = imagePipeline.toDeepSeekImage(imageAssetId)
            if (resolved == null) {
                _imageState.value = _imageState.value.copy(
                    status = ImageAnalysisStatus.UNKNOWN,
                    summary = "Image asset недоступен; требуется повторный импорт",
                    errorCode = "IMAGE_ASSET_RECHECK_REQUIRED",
                    updatedAt = System.currentTimeMillis(),
                )
                fail("Image asset недоступен; визуальный анализ остановлен")
                return
            }
            _imageState.value = _imageState.value.copy(
                status = ImageAnalysisStatus.ANALYZING,
                summary = "Изображение будет передано DeepSeek после явного disclosure",
                errorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            append(
                AgentEventKind.IMAGE,
                "Изображение передаётся DeepSeek для визуального анализа",
                _imageState.value.toJson().toString(),
            )
            resolved
        } else {
            null
        }


        val projectRulesResult = request.workspaceId?.let {
            toolRouter.readProjectRules(it)
        }
        val projectRules = projectRulesResult
            ?.takeIf { it.ok }
            ?.content
            ?.let { AgentRedactor.text(it, 16 * 1024) }
        if (projectRulesResult?.ok == true) {
            append(
                AgentEventKind.INFO,
                "AGENT_RULES.md загружен как дополнительное ограничение",
                "bytes=" + projectRulesResult.content.toByteArray(Charsets.UTF_8).size +
                    "; truncated=" + projectRulesResult.truncated,
            )
        }

        append(
            AgentEventKind.SESSION,
            providerId + " " + request.model + " streaming запущен",
        )

        var inputItems = emptyList<JSONObject>()
        var round = 0

        while (true) {
            val result = provider.streamRound(
                DeepSeekRequest(
                    apiKey = apiKey,
                    baseUrl = request.deepSeekBaseUrl,
                    model = request.model,
                    task = request.task,
                    image = visualImage,
                    projectRules = projectRules,
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
                        if (visualImage != null) {
                            _imageState.value = _imageState.value.copy(
                                status = ImageAnalysisStatus.FAILED,
                                summary = AgentRedactor.text(
                                    event.message,
                                    MAX_ERROR_CHARS,
                                ),
                                errorCode = "IMAGE_ANALYSIS_FAILED",
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
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
            val response = result.response ?: run {
                markUnknown("DeepSeek не вернул completed response")
                return
            }
            val completion = CompletionEvaluator.evaluate(
                result = result,
                nextToolRound = round + 1,
                maxToolRounds = MAX_TOOL_ROUNDS,
            )
            when (completion.decision) {
                CompletionDecision.COMPLETED -> {
                    if (visualImage != null) {
                        _imageState.value = _imageState.value.copy(
                            status = ImageAnalysisStatus.SUCCEEDED,
                            summary = "Визуальный анализ завершён",
                            errorCode = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                        persistAsync()
                    }
                    return
                }

                CompletionDecision.UNKNOWN -> {
                    if (visualImage != null) {
                        _imageState.value = _imageState.value.copy(
                            status = ImageAnalysisStatus.UNKNOWN,
                            summary = "Completion evidence не подтверждён",
                            errorCode = "COMPLETION_RECHECK_REQUIRED",
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    markUnknown(
                        "Completion evidence не подтверждён: " + completion.reason,
                    )
                    return
                }

                CompletionDecision.CONTINUE -> Unit
            }

            round += 1
            val nextInput = responseOutputItems(response).toMutableList()
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
                        val pendingWorkspaceId = request.workspaceId.orEmpty()
                        val pendingPatch = PendingPatch(
                            sessionId = currentSessionId.orEmpty(),
                            workspaceId = pendingWorkspaceId,
                            argumentsJson = call.arguments,
                            preview = preview,
                            canApply = permission.allows(PermissionMode.LOCAL_WRITE),
                            approvalToken = ApprovalTokenFactory.issue(
                                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                                sessionId = currentSessionId.orEmpty(),
                                workspaceId = pendingWorkspaceId,
                                workspaceFingerprint = preview.workspaceFingerprint,
                                path = preview.path,
                                oldSha256 = preview.oldSha256,
                                newSha256 = preview.newSha256,
                                argumentsJson = call.arguments,
                            ),
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
                                (permission.allows(PermissionMode.LOCAL_WRITE)),
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

    override fun approvePendingPatch(approvalToken: String) {
        if (_state.value.status != AgentSessionStatus.WAITING_APPROVAL) return
        val pending = _pendingPatch.value ?: return
        val expectedToken = pending.approvalToken
        val now = System.currentTimeMillis()
        val tokenValid = expectedToken.matches(
            presentedValue = approvalToken,
            operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
            sessionId = pending.sessionId,
            workspaceId = pending.workspaceId,
            workspaceFingerprint = pending.preview.workspaceFingerprint,
            path = pending.preview.path,
            oldSha256 = pending.preview.oldSha256,
            newSha256 = pending.preview.newSha256,
            argumentsJson = pending.argumentsJson,
            now = now,
        )
        if (!tokenValid) {
            val expired = expectedToken.isExpired(now)
            recordDecision(
                kind = "PATCH",
                state = "DENIED",
                detail = if (expired) {
                    "Approval token истёк"
                } else {
                    "Approval token не совпадает с текущей операцией"
                },
            )
            append(
                AgentEventKind.ERROR,
                if (expired) {
                    "Patch approval истёк; нужен новый preview"
                } else {
                    "Patch approval отклонён: token не соответствует текущей операции"
                },
            )
            if (expired) {
                completeInvocation(
                    invocationId = pending.invocationId,
                    state = "UNKNOWN",
                    summary = "Approval token истёк до применения patch",
                )
                clearPendingPatch()
                markUnknown("Approval token истёк; выполните новый preview перед продолжением")
            }
            return
        }
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
                updatePatchRecovery(
                    pending = pending,
                    result = result,
                    status = PatchRecoveryStatus.UNKNOWN,
                )
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
            updatePatchRecovery(
                pending = pending,
                result = result,
                status = PatchRecoveryStatus.APPLIED,
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
        val restoredGitResult = restored.gitOperationResult
        val gitRecoveryRequired = requiresRecovery ||
            restoredGitResult?.status == GitOperationStatus.RUNNING
        val recoveredGitResult = if (
            restoredGitResult?.status == GitOperationStatus.RUNNING
        ) {
            restoredGitResult.copy(
                status = GitOperationStatus.UNKNOWN,
                summary = "Git-операция была прервана при остановке процесса; требуется re-check",
                errorCode = "GIT_RECOVERY_REQUIRED",
            )
        } else {
            restoredGitResult
        }
        lastGitResult = recoveredGitResult
        if (
            !gitRecoveryRequired &&
            recoveredGitResult != null &&
            (
                recoveredGitResult.status == GitOperationStatus.SUCCEEDED ||
                    recoveredGitResult.status == GitOperationStatus.UNKNOWN
            )
        ) {
            cacheGitResult(recoveredGitResult)
        }
        _gitState.value = when {
            gitRecoveryRequired -> GitOperationState(
                status = GitOperationStatus.UNKNOWN,
                sessionId = restored.sessionId,
                operationId = recoveredGitResult?.operationId,
                operation = recoveredGitResult?.operation?.name,
                summary = "Git-операция была прервана при остановке процесса; требуется re-check",
                errorCode = "GIT_RECOVERY_REQUIRED",
                updatedAt = System.currentTimeMillis(),
            )
            recoveredGitResult != null -> recoveredGitResult.toState(restored.sessionId)
            else -> GitOperationState(sessionId = restored.sessionId)
        }
        val restoredActionsState = restored.actionsState
            ?: ActionsOperationState(sessionId = restored.sessionId)
        val actionsRecoveryRequired = requiresRecovery ||
            restoredActionsState.status == ActionsOperationStatus.DISPATCHING ||
            restoredActionsState.status == ActionsOperationStatus.DISCOVERING_RUN ||
            restoredActionsState.status == ActionsOperationStatus.RUNNING
        val recoveredActionsState = if (
            restoredActionsState.status == ActionsOperationStatus.DISPATCHING ||
            restoredActionsState.status == ActionsOperationStatus.DISCOVERING_RUN ||
            restoredActionsState.status == ActionsOperationStatus.RUNNING
        ) {
            restoredActionsState.copy(
                status = ActionsOperationStatus.UNKNOWN,
                summary = "Actions-операция была прервана при остановке процесса; требуется re-check",
                errorCode = "ACTIONS_RECOVERY_REQUIRED",
            )
        } else {
            restoredActionsState
        }
        _actionsState.value = recoveredActionsState
        if (
            !actionsRecoveryRequired &&
            recoveredActionsState.operationId != null &&
            (
                recoveredActionsState.status == ActionsOperationStatus.SUCCEEDED ||
                    recoveredActionsState.status == ActionsOperationStatus.UNKNOWN
            )
        ) {
            cacheActionsResult(recoveredActionsState)
        }
        val restoredInteractive = restored.interactiveState
        _interactiveState.value = restoredInteractive
            ?.takeIf {
                it.status == InteractiveSessionStatus.STARTING ||
                    it.status == InteractiveSessionStatus.RUNNING
            }
            ?.copy(
                status = InteractiveSessionStatus.UNKNOWN,
                summary = "Interactive process не подтверждён после восстановления",
                errorCode = "INTERACTIVE_RECOVERY_REQUIRED",
                updatedAt = System.currentTimeMillis(),
            )
            ?: restoredInteractive
            ?: InteractiveSessionState(sessionId = restored.sessionId)
        _patchRecovery.value = restored.patchRecovery
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
            patchRecovery = _patchRecovery.value,
            actionsState = _actionsState.value,
            gitOperationResult = lastGitResult,
            interactiveState = _interactiveState.value,
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
        operationId: String,
        requiredPermission: PermissionMode,
        description: String,
        action: suspend () -> GitOperationResult,
    ): GitOperationResult {
        externalMutationMutex.lock()
        try {
            check(!closed) { "AgentCore уже закрыт" }
            val cached = cachedGitResult(operationId)
            if (cached != null && cached.sessionId == currentSessionId) {
                val replay = if (cached.operation == operation) {
                    cached
                } else {
                    GitOperationResult(
                        operation = operation,
                        operationId = operationId,
                        sessionId = currentSessionId,
                        status = GitOperationStatus.FAILED,
                        summary = "Operation id уже связан с другой Git-операцией",
                        errorCode = "OPERATION_ID_REUSE",
                    )
                }
                publishGitResult(replay)
                return replay
            }
            if (
                _gitState.value.status == GitOperationStatus.UNKNOWN &&
                _gitState.value.sessionId == currentSessionId
            ) {
                val blocked = GitOperationResult(
                    operation = operation,
                    operationId = operationId,
                    sessionId = currentSessionId,
                    status = GitOperationStatus.UNKNOWN,
                    summary = "Предыдущая Git-операция не подтверждена; сначала выполните re-check",
                    errorCode = "GIT_RECHECK_REQUIRED",
                )
                publishGitResult(blocked)
                return blocked
            }
            if (
                _state.value.status == AgentSessionStatus.RUNNING ||
                _state.value.status == AgentSessionStatus.WAITING_APPROVAL
            ) {
                val result = GitOperationResult(
                    operation = operation,
                    operationId = operationId,
                    status = GitOperationStatus.FAILED,
                    summary = "Сначала завершите текущую сессию Agent Core",
                    errorCode = "SESSION_BUSY",
                )
                val boundResult = result.copy(sessionId = currentSessionId)
                publishGitResult(boundResult)
                return boundResult
            }

            val permission = currentRequestSummary?.permission ?: PermissionMode.READ_ONLY
            if (!permission.allows(requiredPermission)) {
                val result = GitOperationResult(
                    operation = operation,
                    operationId = operationId,
                    status = GitOperationStatus.FAILED,
                    summary = "Для операции нужен permission " + requiredPermission.name,
                    errorCode = "PERMISSION_REQUIRED",
                )
                recordDecision(
                    kind = "GIT",
                    state = "DENIED",
                    detail = operation.name +
                        "; operation_id=" + operationId +
                        "; required=" + requiredPermission.name,
                )
                val boundResult = result.copy(sessionId = currentSessionId)
                publishGitResult(boundResult)
                return boundResult
            }

            recordDecision(
                kind = "GIT",
                state = "APPROVED",
                detail = operation.name +
                    "; operation_id=" + operationId +
                    "; user_action=true",
            )
            append(
                AgentEventKind.APPROVAL,
                "Пользователь подтвердил Git-операцию",
                "operation=" + operation.name + "; operation_id=" + operationId,
            )
            _gitState.value = GitOperationState(
                status = GitOperationStatus.RUNNING,
                operationId = operationId,
                operation = operation.name,
                summary = description,
                updatedAt = System.currentTimeMillis(),
            )
            lastGitResult = GitOperationResult(
                operation = operation,
                sessionId = currentSessionId,
                operationId = operationId,
                status = GitOperationStatus.RUNNING,
                summary = description,
            )
            persistAsync()

            val result = try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                GitOperationResult(
                    operation = operation,
                    operationId = operationId,
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
                operationId = result.operationId ?: operationId,
            )
            if (
                boundResult.status == GitOperationStatus.SUCCEEDED ||
                boundResult.status == GitOperationStatus.UNKNOWN
            ) {
                cacheGitResult(boundResult)
            }
            publishGitResult(boundResult)
            if (boundResult.status == GitOperationStatus.UNKNOWN) {
                markUnknown(
                    boundResult.summary + "; повтор запрещён до re-check",
                )
            }
            return boundResult
        } finally {
            externalMutationMutex.unlock()
        }
    }

    private fun publishGitResult(result: GitOperationResult) {
        if (result.operation != GitOperation.STATUS) {
            lastGitResult = result
        }
        _gitState.value = GitOperationState(
            status = result.status,
            operationId = result.operationId,
            sessionId = result.sessionId ?: currentSessionId,
            repository = result.repository,
            base = result.base,
            expectedHeadSha = result.expectedHeadSha,
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
        if (result.operation != GitOperation.STATUS) {
            persistAsync()
        }
    }

    private fun cachedGitResult(operationId: String): GitOperationResult? {
        return synchronized(gitOperationCache) {
            gitOperationCache[operationId]
        }
    }

    private fun cacheGitResult(result: GitOperationResult) {
        val operationId = result.operationId ?: return
        synchronized(gitOperationCache) {
            gitOperationCache[operationId] = result
            while (gitOperationCache.size > MAX_GIT_OPERATION_CACHE) {
                gitOperationCache.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun cachedActionsResult(operationId: String): ActionsOperationState? {
        return synchronized(actionsOperationCache) {
            actionsOperationCache[operationId]
        }
    }

    private fun cacheActionsResult(result: ActionsOperationState) {
        val operationId = result.operationId ?: return
        synchronized(actionsOperationCache) {
            actionsOperationCache[operationId] = result
            while (actionsOperationCache.size > MAX_ACTIONS_OPERATION_CACHE) {
                actionsOperationCache.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun normalizeOperationId(value: String?): String {
        val operationId = value?.trim()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        require(OPERATION_ID_PATTERN.matches(operationId)) {
            "Operation id имеет недопустимый формат"
        }
        return operationId
    }

    private fun readCredential(kind: CredentialKind): String? {
        val value = credentialVault.read(kind)
        _credentialState.value = credentialVault.state()
        return value
    }

    private fun workspaceIdForActions(): String? {
        return currentRequestSummary?.workspaceId
            ?: workspaceManager.current.value?.id
    }



    private fun updatePatchRecovery(
        pending: PendingPatch,
        result: ToolExecutionResult,
        status: PatchRecoveryStatus,
    ) {
        val operationId = result.patchCheckpointId ?: return
        val workspaceId = pending.workspaceId.takeIf { it.isNotBlank() } ?: return
        _patchRecovery.value = PatchRecoveryState(
            sessionId = pending.sessionId,
            workspaceId = workspaceId,
            operationId = operationId,
            path = pending.preview.path,
            status = status,
            workspaceFingerprintBefore = pending.preview.workspaceFingerprint,
            workspaceFingerprintAfter = result.workspaceFingerprintAfter,
            oldSha256 = pending.preview.oldSha256,
            newSha256 = pending.preview.newSha256,
            errorCode = result.errorCode,
            updatedAt = System.currentTimeMillis(),
        )
        persistAsync()
    }

    private fun recordPatchRollbackFailure(
        operationId: String?,
        path: String? = null,
        summary: String,
        errorCode: String,
    ): PatchRollbackResult {
        val result = PatchRollbackResult(
            operationId = operationId,
            path = path,
            status = PatchRollbackStatus.FAILED,
            summary = summary,
            errorCode = errorCode,
        )
        append(
            AgentEventKind.ERROR,
            result.summary,
            result.toJson().toString(),
        )
        return result
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
            approvalToken = approvalToken.value,
            approvalExpiresAt = approvalToken.expiresAt,
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
            fingerprint = fingerprint,
            repository = repository,
            ref = ref,
            commitSha = commitSha,
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
        payload: String? = null,
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
            schemaVersion = AgentEvent.SCHEMA_VERSION,
            payload = AgentRedactor.text(
                payload,
                AgentEvent.MAX_PAYLOAD_CHARS,
            )?.takeIf { it.isNotBlank() },
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
        const val MAX_GIT_OPERATION_CACHE = 32
        const val MAX_ACTIONS_OPERATION_CACHE = 32
        const val MAX_BASE_URL_CHARS = 512
        const val DEEPSEEK_API_HOST = "api.deepseek.com"
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
        val OPERATION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
        val SHA_PATTERN = Regex("[A-Fa-f0-9]{40,64}")
    }
}
