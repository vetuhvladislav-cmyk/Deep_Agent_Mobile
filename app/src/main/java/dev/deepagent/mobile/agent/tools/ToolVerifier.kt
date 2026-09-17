package dev.deepagent.mobile.agent.tools

import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.security.ToolAuthorization

/**
 * Policy/verifier seam. Model text and tool arguments never grant permission.
 */
object ToolVerifier {
    fun authorize(
        toolName: String,
        permission: PermissionMode,
    ): ToolAuthorization {
        val definition = ToolRegistry.definition(toolName)
            ?: return ToolAuthorization(
                toolName = toolName,
                capability = null,
                requiredPermission = null,
                allowed = false,
                errorCode = "TOOL_NOT_ALLOWED",
            )
        val allowed = permission.allows(definition.requiredPermission)
        return ToolAuthorization(
            toolName = toolName,
            capability = definition.capability,
            requiredPermission = definition.requiredPermission,
            allowed = allowed,
            errorCode = if (allowed) null else "TOOL_FORBIDDEN",
        )
    }
}
