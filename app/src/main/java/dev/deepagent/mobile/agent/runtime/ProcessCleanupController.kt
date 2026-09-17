package dev.deepagent.mobile.agent.runtime

enum class ProcessCleanupStatus {
    CLEAN,
    CLEANUP_UNKNOWN,
}

data class ProcessCleanupResult(
    val status: ProcessCleanupStatus,
    val summary: String,
    val sessionId: String? = null,
)

interface ProcessCleanupController {
    suspend fun cleanup(
        sessionId: String?,
        reason: String,
    ): ProcessCleanupResult
}
