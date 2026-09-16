package dev.deepagent.mobile.agent.protocol

import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentWorkspaceSnapshot
import dev.deepagent.mobile.agent.model.PendingPatchApproval
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

    suspend fun submit(request: AgentRequest)

    suspend fun importWorkspace(
        uri: String,
        displayName: String? = null,
    ): AgentWorkspaceSnapshot

    fun approvePendingPatch()

    fun rejectPendingPatch()

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
    const val BUILD = "build"
    const val APPROVAL = "approval"
    const val ARTIFACT = "artifact"
    const val ERROR = "error"
    const val INFO = "info"
}
