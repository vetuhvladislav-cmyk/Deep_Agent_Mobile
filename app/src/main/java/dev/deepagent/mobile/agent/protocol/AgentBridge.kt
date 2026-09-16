package dev.deepagent.mobile.agent.protocol

import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Стабильная внутренняя граница между Android UI и Agent Core.
 *
 * DSH/WebView, локальный runtime и удалённые Actions не должны становиться
 * контрактом UI. В дальнейшем любой исполнитель подключается за этой границей.
 */
interface AgentBridge {
    val state: StateFlow<AgentSessionState>
    val events: StateFlow<List<AgentEvent>>

    suspend fun submit(request: AgentRequest)

    fun cancel()

    fun clearEvents()
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
    const val ERROR = "error"
    const val INFO = "info"
}
