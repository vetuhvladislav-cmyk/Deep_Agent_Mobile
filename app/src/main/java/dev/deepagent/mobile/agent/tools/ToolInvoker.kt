package dev.deepagent.mobile.agent.tools

import dev.deepagent.mobile.agent.model.PermissionMode

/**
 * Execution seam for ToolRouter. It preserves the current router API while
 * keeping orchestration independent from the concrete dispatch implementation.
 */
class ToolInvoker(
    private val router: ToolRouter,
) {
    suspend fun execute(
        toolName: String,
        argumentsJson: String,
        workspaceId: String? = null,
        permission: PermissionMode = PermissionMode.READ_ONLY,
    ): ToolExecutionResult = router.execute(
        toolName = toolName,
        argumentsJson = argumentsJson,
        workspaceId = workspaceId,
        permission = permission,
    )

    suspend fun previewPatch(
        argumentsJson: String,
        workspaceId: String? = null,
        permission: PermissionMode = PermissionMode.READ_ONLY,
    ): ToolExecutionResult = router.previewPatch(
        argumentsJson = argumentsJson,
        workspaceId = workspaceId,
        permission = permission,
    )
}
