package dev.deepagent.mobile.agent.security

import java.util.UUID

/**
 * Состояние approval-binding.
 *
 * Токен одноразовый: [ISSUED] означает, что side effect ещё не начинался и
 * binding можно предъявить. После первого подтверждения токен переходит в
 * [CONSUMED] и повторно не активируется. [REVOKED] выставляется при отмене
 * сессии или downgrade и не возвращается назад.
 */
enum class ApprovalState {
    ISSUED,
    CONSUMED,
    REVOKED,
}

data class ApprovalConsumption(
    val tokenValue: String,
    val operation: String,
    val sessionId: String,
    val operationId: String?,
    val bootId: String,
    val consumedAtMonotonicMs: Long,
) {
    fun redactedSummary(): String {
        return "operation=" + operation +
            "; session=" + sessionId +
            "; operation_id=" + (operationId ?: "-")
    }
}

/**
 * Реестр одноразовости approval-токенов.
 *
 * Проверка binding-полей отвечает на вопрос «подтверждается ли ровно тот
 * preview, который был показан». Она не отвечает на вопрос «предъявляется ли
 * этот токен впервые». Без второго ответа один и тот же токен можно предъявить
 * повторно, и это не будет обнаружено: `clearPendingPatch()` лишь обнуляет
 * StateFlow и не является доказательством.
 *
 * Реестр намеренно не выдаёт новые токены и не принимает решений о правах: он
 * только фиксирует факт первого предъявления. Решение остаётся за PDP/PEP.
 */
class ApprovalConsumptionRegistry(
    private val timeSource: AgentTimeSource = ProcessAgentTimeSource(),
) {
    private val consumed = linkedMapOf<String, ApprovalConsumption>()
    private val revoked = linkedSetOf<String>()

    @Synchronized
    fun stateOf(tokenValue: String): ApprovalState {
        val normalized = tokenValue.trim()
        if (normalized.isEmpty()) return ApprovalState.REVOKED
        if (revoked.contains(normalized)) return ApprovalState.REVOKED
        return if (consumed.containsKey(normalized)) {
            ApprovalState.CONSUMED
        } else {
            ApprovalState.ISSUED
        }
    }

    /**
     * Атомарно переводит токен в [ApprovalState.CONSUMED]. Возвращает null, если
     * токен уже был использован или отозван: в этом случае side effect
     * запрещён.
     */
    @Synchronized
    fun consume(
        tokenValue: String,
        operation: String,
        sessionId: String,
        operationId: String? = null,
    ): ApprovalConsumption? {
        val normalized = tokenValue.trim()
        if (normalized.isEmpty()) return null
        if (revoked.contains(normalized)) return null
        if (consumed.containsKey(normalized)) return null

        val record = ApprovalConsumption(
            tokenValue = normalized,
            operation = operation,
            sessionId = sessionId,
            operationId = operationId,
            bootId = timeSource.bootId,
            consumedAtMonotonicMs = timeSource.monotonicMillis(),
        )
        consumed[normalized] = record
        evictOldestIfNeeded()
        return record
    }

    @Synchronized
    fun revoke(tokenValue: String) {
        val normalized = tokenValue.trim()
        if (normalized.isEmpty()) return
        revoked += normalized
        consumed.remove(normalized)
    }

    @Synchronized
    fun revocationCount(): Int = revoked.size

    @Synchronized
    fun consumptionCount(): Int = consumed.size

    private fun evictOldestIfNeeded() {
        while (consumed.size > MAX_TRACKED_TOKENS) {
            val oldest = consumed.keys.firstOrNull() ?: return
            consumed.remove(oldest)
        }
    }

    companion object {
        /**
         * Токены живут в памяти процесса, поэтому реестр растёт медленно. Лимит
         * нужен только чтобы исключить неограниченный рост при долгой сессии.
         */
        const val MAX_TRACKED_TOKENS = 4_096

        fun newTokenValue(): String = "approval_" + UUID.randomUUID()
    }
}
