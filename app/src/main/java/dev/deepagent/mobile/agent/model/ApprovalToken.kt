package dev.deepagent.mobile.agent.model

import java.security.MessageDigest
import java.util.UUID

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
    val path: String,
    val oldSha256: String?,
    val newSha256: String,
    val argumentsSha256: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now >= expiresAt
    }

    fun matches(
        presentedValue: String,
        operation: String,
        sessionId: String,
        workspaceId: String,
        workspaceFingerprint: String,
        path: String,
        oldSha256: String?,
        newSha256: String,
        argumentsJson: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return !isExpired(now) &&
            presentedValue.trim() == value &&
            this.operation == operation &&
            this.sessionId == sessionId &&
            this.workspaceId == workspaceId &&
            this.workspaceFingerprint == workspaceFingerprint &&
            this.path == path &&
            this.oldSha256 == oldSha256 &&
            this.newSha256 == newSha256 &&
            this.argumentsSha256 == ApprovalTokenFactory.argumentsDigest(argumentsJson)
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
            path = path,
            oldSha256 = oldSha256,
            newSha256 = newSha256,
            argumentsSha256 = argumentsDigest(argumentsJson),
            issuedAt = now,
            expiresAt = now + ttlMs,
        )
    }

    fun argumentsDigest(argumentsJson: String): String {
        return sha256(argumentsJson.trim())
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
