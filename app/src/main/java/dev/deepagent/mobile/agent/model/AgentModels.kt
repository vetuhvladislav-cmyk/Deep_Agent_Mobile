package dev.deepagent.mobile.agent.model

import java.util.UUID

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
