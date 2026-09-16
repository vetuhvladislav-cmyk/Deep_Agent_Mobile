package dev.deepagent.mobile.agent.model

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
)
