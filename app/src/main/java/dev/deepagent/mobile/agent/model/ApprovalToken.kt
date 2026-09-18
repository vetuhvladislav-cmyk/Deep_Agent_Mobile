package dev.deepagent.mobile.agent.model

import dev.deepagent.mobile.agent.security.AgentTimeSource
import dev.deepagent.mobile.agent.security.CanonicalArgs
import dev.deepagent.mobile.agent.security.ProcessAgentTimeSource

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
    val operationId: String? = null,
    /**
     * Boot identity на момент выдачи. Токен, выданный в прошлом запуске
     * процесса, не активируется: доказать его окно после перезапуска нельзя.
     */
    val bootId: String = ApprovalTokenFactory.UNBOUND_BOOT_ID,
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
        operationId: String? = null,
        bootId: String = ApprovalTokenFactory.UNBOUND_BOOT_ID,
    ): Boolean {
        return bootIdMatches(bootId) &&
            !isExpired(now) &&
            this.toolName == toolName &&
            this.sessionId == sessionId &&
            this.workspaceId == workspaceId &&
            this.workspaceFingerprint == workspaceFingerprint &&
            this.targetSha == targetSha &&
            this.path == path &&
            this.oldSha256 == oldSha256 &&
            this.newSha256 == newSha256 &&
            this.canonicalArgsSha256 == canonicalArgsSha256 &&
            this.operationId == operationId
    }

    /**
     * Привязка boot identity проверяется отдельно от остальных полей, потому что
     * причина отказа важна для UI: это не «токен не от того preview», а
     * «процесс перезапускался, нужен новый preview».
     */
    fun bootIdMatches(bootId: String): Boolean {
        if (this.bootId == ApprovalTokenFactory.UNBOUND_BOOT_ID) return true
        if (bootId == ApprovalTokenFactory.UNBOUND_BOOT_ID) return true
        return this.bootId == bootId
    }
}

/**
 * Одноразовый in-memory binding для опасной операции.
 *
 * Значение токена является непрозрачным для UI; остальные поля используются
 * Agent Core для проверки того, что подтверждается ровно тот preview, который
 * был показан пользователю.
 *
 * Проверка binding не заменяет проверку одноразовости: повторное предъявление
 * обнаружит `ApprovalConsumptionRegistry`.
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
    val operationId: String? = null,
    val bootId: String = ApprovalTokenFactory.UNBOUND_BOOT_ID,
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
            operationId = operationId,
            bootId = bootId,
        )

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return binding.isExpired(now)
    }

    /**
     * `now` — монотонное время в миллисекундах. Wall clock нельзя использовать
     * для решения о доступе: его перевод назад продлевает окно approval.
     */
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
        operationId: String? = null,
        bootId: String = ApprovalTokenFactory.UNBOUND_BOOT_ID,
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
                operationId = operationId,
                bootId = bootId,
            )
    }
}

object ApprovalTokenFactory {
    const val APPLY_PATCH_OPERATION = "apply_patch"
    const val DEFAULT_TTL_MS = 5 * 60 * 1_000L
    const val UNBOUND_BOOT_ID = "-"

    fun issue(
        operation: String,
        sessionId: String,
        workspaceId: String,
        workspaceFingerprint: String,
        path: String,
        oldSha256: String?,
        newSha256: String,
        argumentsJson: String,
        timeSource: AgentTimeSource = defaultTimeSource,
        targetSha: String? = null,
        ttlMs: Long = DEFAULT_TTL_MS,
        operationId: String? = null,
    ): ApprovalToken {
        return issue(
            operation = operation,
            sessionId = sessionId,
            workspaceId = workspaceId,
            workspaceFingerprint = workspaceFingerprint,
            targetSha = targetSha,
            path = path,
            oldSha256 = oldSha256,
            newSha256 = newSha256,
            argumentsJson = argumentsJson,
            issuedAt = timeSource.monotonicMillis(),
            bootId = timeSource.bootId,
            ttlMs = ttlMs,
            operationId = operationId,
        )
    }

    /**
     * Перегрузка с явными значениями. Нужна тестам и вызовам, которые уже
     * передают момент выдачи; `bootId` при этом остаётся обязательным для
     * проверки, поэтому по умолчанию берётся из текущего процесса.
     */
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
        issuedAt: Long,
        bootId: String = defaultTimeSource.bootId,
        ttlMs: Long = DEFAULT_TTL_MS,
        operationId: String? = null,
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
        require(bootId.isNotBlank()) { "Boot ID для approval не задан" }

        return ApprovalToken(
            value = "approval_" + java.util.UUID.randomUUID(),
            operation = operation,
            sessionId = sessionId,
            workspaceId = workspaceId,
            workspaceFingerprint = workspaceFingerprint,
            targetSha = targetSha,
            path = path,
            oldSha256 = oldSha256,
            newSha256 = newSha256,
            argumentsSha256 = argumentsDigest(argumentsJson),
            issuedAt = issuedAt,
            expiresAt = issuedAt + ttlMs,
            operationId = operationId,
            bootId = bootId,
        )
    }

    fun argumentsDigest(argumentsJson: String): String {
        return CanonicalArgs.sha256(argumentsJson)
    }

    private val defaultTimeSource: AgentTimeSource by lazy { ProcessAgentTimeSource() }
}
