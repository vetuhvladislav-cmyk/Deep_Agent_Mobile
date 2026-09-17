package dev.deepagent.mobile.agent.model

import dev.deepagent.mobile.agent.security.CanonicalArgs
import java.util.UUID

data class ApprovalBinding(
    val toolName: String,
    val sessionId: String,
    val workspaceId: String,
    val workspaceFingerprint: String,
    val targetSha: String?,
    val path: String,
    val oldSha256: String?,
    val newSha256: String,
    val canonicalArgsSha256: String,
    val expiresAt: Long,
) {
    fun isExpired(now: Long): Boolean = now >= expiresAt

    fun matches(
        toolName: String,
        sessionId: String,
        workspaceId: String,
        workspaceFingerprint: String,
        targetSha: String?,
        path: String,
        oldSha256: String?,
        newSha256: String,
        canonicalArgsSha256: String,
        now: Long,
    ): Boolean {
        return !isExpired(now) &&
            this.toolName == toolName &&
            this.sessionId == sessionId &&
            this.workspaceId == workspaceId &&
            this.workspaceFingerprint == workspaceFingerprint &&
            this.targetSha == targetSha &&
            this.path == path &&
            this.oldSha256 == oldSha256 &&
            this.newSha256 == newSha256 &&
            this.canonicalArgsSha256 == canonicalArgsSha256
    }
}

/**
 * Одноразовый in-memory binding для опасной операции.
 *
 * Значение токена является непрозрачным для UI; остальные поля используются
 * Agent Core для проверки того, что подтверждается ровно тот preview, который
 * был показан пользователю.
 */
data class ApprovalToken(
    val value: String,
    val operation: String,
    val sessionId: String,
    val workspaceId: String,
    val workspaceFingerprint: String,
    val targetSha: String? = null,
    val path: String,
    val oldSha256: String?,
    val newSha256: String,
    val argumentsSha256: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val binding: ApprovalBinding
        get() = ApprovalBinding(
            toolName = operation,
            sessionId = sessionId,
            workspaceId = workspaceId,
            workspaceFingerprint = workspaceFingerprint,
            targetSha = targetSha,
            path = path,
            oldSha256 = oldSha256,
            newSha256 = newSha256,
            canonicalArgsSha256 = argumentsSha256,
            expiresAt = expiresAt,
        )

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return binding.isExpired(now)
    }

    fun matches(
        presentedValue: String,
        operation: String,
        sessionId: String,
        workspaceId: String,
        workspaceFingerprint: String,
        targetSha: String? = null,
        path: String,
        oldSha256: String?,
        newSha256: String,
        argumentsJson: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return presentedValue.trim() == value &&
            binding.matches(
                toolName = operation,
                sessionId = sessionId,
                workspaceId = workspaceId,
                workspaceFingerprint = workspaceFingerprint,
                targetSha = targetSha,
                path = path,
                oldSha256 = oldSha256,
                newSha256 = newSha256,
                canonicalArgsSha256 = ApprovalTokenFactory.argumentsDigest(argumentsJson),
                now = now,
            )
    }
}

object ApprovalTokenFactory {
    const val APPLY_PATCH_OPERATION = "apply_patch"
    const val DEFAULT_TTL_MS = 5 * 60 * 1_000L

    fun issue(
        operation: String,
        sessionId: String,
        workspaceId: String,
        workspaceFingerprint: String,
        targetSha: String? = null,
        path: String,
        oldSha256: String?,
        newSha256: String,
        argumentsJson: String,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): ApprovalToken {
        require(operation.isNotBlank()) { "Операция approval не задана" }
        require(sessionId.isNotBlank()) { "Session ID для approval не задан" }
        require(workspaceId.isNotBlank()) { "Workspace ID для approval не задан" }
        require(workspaceFingerprint.isNotBlank()) {
            "Fingerprint workspace для approval не задан"
        }
        require(path.isNotBlank()) { "Путь для approval не задан" }
        require(newSha256.isNotBlank()) { "Новый SHA для approval не задан" }
        require(argumentsJson.isNotBlank()) { "Аргументы approval не заданы" }
        require(ttlMs > 0L) { "TTL approval должен быть положительным" }

        return ApprovalToken(
            value = "approval_" + UUID.randomUUID(),
            operation = operation,
            sessionId = sessionId,
            workspaceId = workspaceId,
            workspaceFingerprint = workspaceFingerprint,
            targetSha = targetSha,
            path = path,
            oldSha256 = oldSha256,
            newSha256 = newSha256,
            argumentsSha256 = argumentsDigest(argumentsJson),
            issuedAt = now,
            expiresAt = now + ttlMs,
        )
    }

    fun argumentsDigest(argumentsJson: String): String {
        return CanonicalArgs.sha256(argumentsJson)
    }

}
