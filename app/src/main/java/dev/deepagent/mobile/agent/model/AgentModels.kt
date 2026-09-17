package dev.deepagent.mobile.agent.model

import java.util.UUID
import org.json.JSONObject

enum class ExecutionTarget {
    AUTO,
    LOCAL_LITE,
    REMOTE_ACTIONS,
}

enum class PermissionMode {
    READ_ONLY,
    LOCAL_WRITE,
    GITHUB_WRITE,
    PR_CREATE,
    MERGE_RELEASE,
}

enum class AgentEventKind {
    SESSION,
    PLAN,
    REASONING,
    OUTPUT,
    TOOL,
    BUILD,
    APPROVAL,
    ARTIFACT,
    ERROR,
    INFO,
}

enum class AgentSessionStatus {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

data class ImageAttachment(
    val dataUrl: String,
    val mediaType: String,
    val detail: String = "auto",
    val displayName: String? = null,
)

data class AgentRequest(
    val task: String,
    val target: ExecutionTarget = ExecutionTarget.AUTO,
    val permission: PermissionMode = PermissionMode.READ_ONLY,
    val image: ImageAttachment? = null,
    val deepSeekApiKey: String? = null,
    val deepSeekBaseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val githubToken: String? = null,
    val repository: String? = null,
    val workflow: String? = null,
    val ref: String = "main",
    val sessionId: String? = null,
    val workspaceId: String? = null,
)

data class AgentEvent(
    val kind: AgentEventKind,
    val message: String,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sessionId: String? = null,
    val eventId: String = UUID.randomUUID().toString(),
    val sequence: Long = 0L,
    val workspaceId: String? = null,
    val invocationId: String? = null,
)

data class AgentSessionState(
    val status: AgentSessionStatus = AgentSessionStatus.IDLE,
    val target: ExecutionTarget? = null,
    val task: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val lastError: String? = null,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val eventCursor: Long = 0L,
    val recoveryRequired: Boolean = false,
)

    
data class AgentWorkspaceSnapshot(
    val id: String,
    val displayName: String,
    val sourceType: String,
    val fileCount: Int,
    val totalBytes: Long,
    val importedAt: Long,
)

data class PendingPatchApproval(
    val sessionId: String,
    val workspaceId: String,
    val path: String,
    val workspaceFingerprint: String,
    val oldSha256: String?,
    val newSha256: String,
    val unifiedDiff: String,
    val canApply: Boolean,
)


enum class PatchRecoveryStatus {
    APPLIED,
    ROLLED_BACK,
    UNKNOWN,
}

data class PatchRecoveryState(
    val sessionId: String,
    val workspaceId: String,
    val operationId: String,
    val path: String,
    val status: PatchRecoveryStatus,
    val workspaceFingerprintBefore: String,
    val workspaceFingerprintAfter: String?,
    val oldSha256: String?,
    val newSha256: String,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", sessionId)
        .put("workspace_id", workspaceId)
        .put("operation_id", operationId)
        .put("path", path)
        .put("status", status.name)
        .put("workspace_fingerprint_before", workspaceFingerprintBefore)
        .put("workspace_fingerprint_after", workspaceFingerprintAfter)
        .put("old_sha256", oldSha256)
        .put("new_sha256", newSha256)
        .put("error_code", errorCode)
        .put("updated_at", updatedAt)

    companion object {
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
        private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")

        fun fromJson(value: JSONObject): PatchRecoveryState? {
            val sessionId = value.optString("session_id").trim()
            val workspaceId = value.optString("workspace_id").trim()
            val operationId = value.optString("operation_id").trim()
            val path = value.optString("path").trim()
            val beforeFingerprint = value.optString("workspace_fingerprint_before").trim()
            val afterFingerprint = value.optString("workspace_fingerprint_after")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val oldSha = value.optString("old_sha256")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val newSha = value.optString("new_sha256").trim()
            if (
                !IDENTIFIER_PATTERN.matches(sessionId) ||
                !IDENTIFIER_PATTERN.matches(workspaceId) ||
                !IDENTIFIER_PATTERN.matches(operationId) ||
                path.isBlank() ||
                path.length > 512 ||
                path.contains('\u0000') ||
                !SHA256_PATTERN.matches(beforeFingerprint) ||
                (afterFingerprint != null && !SHA256_PATTERN.matches(afterFingerprint)) ||
                (oldSha != null && !SHA256_PATTERN.matches(oldSha)) ||
                !SHA256_PATTERN.matches(newSha)
            ) {
                return null
            }
            val parsedStatus = runCatching {
                PatchRecoveryStatus.valueOf(value.optString("status"))
            }.getOrNull() ?: PatchRecoveryStatus.UNKNOWN
            val safeStatus = if (
                parsedStatus == PatchRecoveryStatus.APPLIED &&
                afterFingerprint == null
            ) {
                PatchRecoveryStatus.UNKNOWN
            } else {
                parsedStatus
            }
            return PatchRecoveryState(
                sessionId = sessionId,
                workspaceId = workspaceId,
                operationId = operationId,
                path = path,
                status = safeStatus,
                workspaceFingerprintBefore = beforeFingerprint,
                workspaceFingerprintAfter = afterFingerprint,
                oldSha256 = oldSha,
                newSha256 = newSha,
                errorCode = value.optString("error_code")
                    .trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.take(160),
                updatedAt = value.optLong("updated_at", System.currentTimeMillis()),
            )
        }
    }
}

enum class PatchRollbackStatus {
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

data class PatchRollbackResult(
    val operationId: String? = null,
    val path: String? = null,
    val status: PatchRollbackStatus,
    val summary: String,
    val workspaceFingerprintBefore: String? = null,
    val workspaceFingerprintAfter: String? = null,
    val errorCode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("operation_id", operationId)
        .put("path", path)
        .put("status", status.name)
        .put("summary", summary)
        .put("workspace_fingerprint_before", workspaceFingerprintBefore)
        .put("workspace_fingerprint_after", workspaceFingerprintAfter)
        .put("error_code", errorCode)
}


internal object AgentRedactor {
    private val dataUrlPattern = Regex(
        """data:[^;\s]+;base64,[A-Za-z0-9+/=]+""",
        RegexOption.IGNORE_CASE,
    )
    private val secretFieldPattern = Regex(
        """(?i)("?(?:authorization|cookie|token|api[_-]?key|password|secret)"?\s*:\s*)("[^"]*"|'[^']*'|[^,\s}]+)""",
    )
    private val secretAssignmentPattern = Regex(
        """(?i)(\b(?:authorization|cookie|token|api[_-]?key|password|secret)\s*[=:]\s*)([^\s,;]+)""",
    )
    private val bearerPattern = Regex(
        """(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*""",
    )
    private val knownTokenPattern = Regex(
        """\b(?:ghp_|github_pat_|sk-)[A-Za-z0-9_-]{8,}\b""",
    )

    fun text(value: String?, maxChars: Int): String? {
        if (value == null) return null
        val limit = maxChars.coerceAtLeast(1)
        var result = value
        result = result.replace(dataUrlPattern, "<redacted-data-url>")
        result = result.replace(bearerPattern, "Bearer <redacted>")
        result = result.replace(knownTokenPattern, "<redacted-token>")
        result = result.replace(secretFieldPattern) { match ->
            match.groupValues[1] + "\"<redacted>\""
        }
        result = result.replace(secretAssignmentPattern) { match ->
            match.groupValues[1] + "<redacted>"
        }
        return if (result.length <= limit) {
            result
        } else {
            result.take(limit) + "\n[truncated]"
        }
    }
}
