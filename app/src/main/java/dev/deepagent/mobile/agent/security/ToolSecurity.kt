package dev.deepagent.mobile.agent.security

import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.PermissionMode
import org.json.JSONObject

enum class ToolCapability(
    val requiredPermission: PermissionMode,
) {
    READ_ONLY(PermissionMode.READ_ONLY),
    LOCAL_WRITE(PermissionMode.LOCAL_WRITE),
    GITHUB_WRITE(PermissionMode.GITHUB_WRITE),
    PR_CREATE(PermissionMode.PR_CREATE),
    MERGE_RELEASE(PermissionMode.MERGE_RELEASE),
}

data class ToolAuthorization(
    val toolName: String,
    val capability: ToolCapability?,
    val requiredPermission: PermissionMode?,
    val allowed: Boolean,
    val errorCode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tool", AgentRedactor.text(toolName, 160))
        .put("capability", capability?.name)
        .put("required_permission", requiredPermission?.name)
        .put("allowed", allowed)
        .put("error_code", AgentRedactor.text(errorCode, 96))
}

enum class ToolTrust {
    SYSTEM,
    UNTRUSTED_WORKSPACE,
    UNTRUSTED_PROVIDER,
}

data class ToolOutputEnvelope(
    val toolName: String,
    val capability: ToolCapability,
    val ok: Boolean,
    val summary: String,
    val content: String,
    val truncated: Boolean,
    val errorCode: String?,
    val trust: ToolTrust = ToolTrust.UNTRUSTED_WORKSPACE,
    val operationId: String? = null,
    val sessionId: String? = null,
    val canonicalArgsSha256: String? = null,
) {
    fun toModelJson(): String {
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("tool", AgentRedactor.text(toolName, MAX_IDENTIFIER_CHARS))
            .put("capability", capability.name)
            .put("ok", ok)
            .put("summary", AgentRedactor.text(summary, MAX_SUMMARY_CHARS))
            .put(
                "content",
                AgentRedactor.text(content, MAX_CONTENT_CHARS),
            )
            .put("truncated", truncated)
            .put("error_code", AgentRedactor.text(errorCode, MAX_ERROR_CODE_CHARS))
            .put("trust", trust.name)
            .put("content_is_data", true)
            .put("instructions_are_data", true)
            .put("operation_id", AgentRedactor.text(operationId, MAX_IDENTIFIER_CHARS))
            .put("session_id", AgentRedactor.text(sessionId, MAX_IDENTIFIER_CHARS))
            .put(
                "canonical_args_sha256",
                AgentRedactor.text(canonicalArgsSha256, 128),
            )
            .toString()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_IDENTIFIER_CHARS = 160
        private const val MAX_SUMMARY_CHARS = 2_000
        private const val MAX_CONTENT_CHARS = 128 * 1024
        private const val MAX_ERROR_CODE_CHARS = 96

        fun untrusted(
            toolName: String,
            capability: ToolCapability,
            ok: Boolean,
            summary: String,
            content: String,
            truncated: Boolean,
            errorCode: String?,
            operationId: String? = null,
            sessionId: String? = null,
            canonicalArgsSha256: String? = null,
        ): ToolOutputEnvelope {
            return ToolOutputEnvelope(
                toolName = toolName,
                capability = capability,
                ok = ok,
                summary = summary,
                content = content,
                truncated = truncated,
                errorCode = errorCode,
                trust = ToolTrust.UNTRUSTED_WORKSPACE,
                operationId = operationId,
                sessionId = sessionId,
                canonicalArgsSha256 = canonicalArgsSha256,
            )
        }
    }
}
