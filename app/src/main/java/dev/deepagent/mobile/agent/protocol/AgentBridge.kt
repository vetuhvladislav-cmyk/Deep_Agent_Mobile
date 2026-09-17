package dev.deepagent.mobile.agent.protocol

import dev.deepagent.mobile.agent.model.ActionsArtifactRequest
import dev.deepagent.mobile.agent.model.RuntimeState
import dev.deepagent.mobile.agent.model.InteractiveCommandRequest
import dev.deepagent.mobile.agent.model.InteractiveSessionState
import dev.deepagent.mobile.agent.model.ActionsArtifactSaveResult
import dev.deepagent.mobile.agent.model.ActionsOperationState
import dev.deepagent.mobile.agent.model.ActionsRunRequest
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.CredentialState
import dev.deepagent.mobile.agent.model.JournalExportResult
import dev.deepagent.mobile.agent.model.ImageAnalysisState
import dev.deepagent.mobile.agent.model.WorkspaceCatalogState
import dev.deepagent.mobile.agent.model.AgentWorkspaceSnapshot
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.git.GitBranchRequest
import dev.deepagent.mobile.agent.git.GitCommitRequest
import dev.deepagent.mobile.agent.git.GitOperationResult
import dev.deepagent.mobile.agent.git.GitOperationState
import dev.deepagent.mobile.agent.git.GitPullRequestRequest
import dev.deepagent.mobile.agent.git.GitPushRequest
import dev.deepagent.mobile.agent.model.PendingPatchApproval
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.model.PatchRecoveryState
import dev.deepagent.mobile.agent.model.PatchRollbackResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Стабильная внутренняя граница между Android UI и Agent Core.
 *
 * Headless DSH, локальный runtime и удалённые Actions не должны становиться
 * контрактом UI. В дальнейшем любой исполнитель подключается за этой границей.
 */
interface AgentBridge {
    val state: StateFlow<AgentSessionState>
    val events: StateFlow<List<AgentEvent>>
    val workspace: StateFlow<AgentWorkspaceSnapshot?>
    val pendingApproval: StateFlow<PendingPatchApproval?>
    val git: StateFlow<GitOperationState>
    val patchRecovery: StateFlow<PatchRecoveryState?>
    val actions: StateFlow<ActionsOperationState>
    val runtime: StateFlow<RuntimeState>
    val interactive: StateFlow<InteractiveSessionState>
    val image: StateFlow<ImageAnalysisState>
    val workspaceCatalog: StateFlow<WorkspaceCatalogState>
    val credentials: StateFlow<CredentialState>

    suspend fun submit(request: AgentRequest)

    fun configureCredentials(
        deepSeekApiKey: String?,
        githubToken: String?,
    ): CredentialState

    fun clearCredentials()

    suspend fun exportJournal(destinationUri: String): JournalExportResult

    suspend fun importWorkspace(
        uri: String,
        displayName: String? = null,
    ): AgentWorkspaceSnapshot

    suspend fun prepareImage(
        uri: String,
        displayName: String? = null,
    ): ImageAnalysisState

    fun clearImage()

    suspend fun selectWorkspace(workspaceId: String): AgentWorkspaceSnapshot

    suspend fun refreshWorkspace(): WorkspaceCatalogState

    fun approvePendingPatch()

    fun rejectPendingPatch()

    suspend fun inspectGit(): GitOperationResult

    suspend fun createGitBranch(request: GitBranchRequest): GitOperationResult

    suspend fun commitGit(request: GitCommitRequest): GitOperationResult

    suspend fun pushGit(request: GitPushRequest): GitOperationResult

    suspend fun createPullRequest(
        request: GitPullRequestRequest,
        githubToken: String,
    ): GitOperationResult

    suspend fun startRuntime(permission: PermissionMode = PermissionMode.READ_ONLY): RuntimeState

    suspend fun runInteractive(
        request: InteractiveCommandRequest,
    ): InteractiveSessionState

    fun sendInteractiveInput(sessionId: String, input: String): Boolean

    fun cancelInteractive()

    suspend fun stopRuntime(): RuntimeState

    suspend fun runActions(request: ActionsRunRequest): ActionsOperationState

    suspend fun saveVerifiedArtifact(
        request: ActionsArtifactRequest,
    ): ActionsArtifactSaveResult

    suspend fun rollbackLastPatch(): PatchRollbackResult

    fun cancel()

    fun clearEvents()

    fun close()
}

/**
 * Имена событий протокола AgentBridge v1. Используются в документации,
 * диагностике и будущей JSON/IPC-адаптации.
 */
object AgentBridgeProtocol {
    const val VERSION = "agent-bridge/v1"

    const val SESSION = "session"
    const val PLAN = "plan"
    const val REASONING = "reasoning"
    const val OUTPUT = "output"
    const val TOOL = "tool"
    const val IMAGE = "image"
    const val BUILD = "build"
    const val APPROVAL = "approval"
    const val ARTIFACT = "artifact"
    const val ERROR = "error"
    const val INFO = "info"
}
