package dev.deepagent.mobile.agent.tools

import dev.deepagent.mobile.agent.model.PermissionMode

/**
 * Capability-filtered registry seam. The existing ToolRouter remains the
 * compatibility owner while definitions are extracted incrementally.
 */
object ToolRegistry {
    fun definitions(
        permission: PermissionMode = PermissionMode.READ_ONLY,
    ): List<AgentToolDefinition> = ToolRouter.definitions(permission)

    fun definition(toolName: String): AgentToolDefinition? =
        ToolRouter.definition(toolName)
}
