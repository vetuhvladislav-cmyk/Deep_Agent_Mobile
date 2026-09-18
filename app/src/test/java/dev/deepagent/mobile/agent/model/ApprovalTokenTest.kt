package dev.deepagent.mobile.agent.model

import dev.deepagent.mobile.agent.security.ApprovalConsumptionRegistry
import dev.deepagent.mobile.agent.security.ApprovalState
import dev.deepagent.mobile.agent.security.FixedAgentTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalTokenTest {

    @Test
    fun tokenBindsOperationWorkspaceShaAndArguments() {
        val token = issue(now = 10_000L)

        assertTrue(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = 10_001L,
            ),
        )
        assertFalse(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = "changed-fingerprint",
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = 10_001L,
            ),
        )
        assertFalse(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = """{"path":"src/Other.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
                now = 10_001L,
            ),
        )
    }

    @Test
    fun tokenExpiresAndCannotBeReusedAfterTtl() {
        val token = issue(now = 20_000L)

        assertFalse(token.isExpired(now = 20_099L))
        assertTrue(token.isExpired(now = 20_100L))
        assertFalse(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = 20_100L,
            ),
        )
    }

    @Test
    fun tokenBindsTargetSha() {
        val token = issue(now = 30_000L, targetSha = "commit-1")

        assertTrue(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                targetSha = "commit-1",
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = 30_001L,
            ),
        )
        assertFalse(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                targetSha = "commit-2",
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = 30_001L,
            ),
        )
    }

    /**
     * SEC-02: TTL считается по монотонному времени. Перевод wall clock назад не
     * продлевает окно approval, потому что wall clock в решении не участвует.
     */
    @Test
    fun wallClockRollbackDoesNotExtendApprovalWindow() {
        val time = FixedAgentTimeSource(
            monotonicMillis = 0L,
            wallMillis = 1_000_000L,
        )
        val token = issueWith(time, ttlMs = 100L)

        assertFalse(token.isExpired(now = time.monotonicMillis()))

        // Часы устройства переведены на час назад.
        time.setWallClock(1_000_000L - 3_600_000L)
        time.advanceMonotonicBy(99L)
        assertFalse(token.isExpired(now = time.monotonicMillis()))

        // Монотонное время прошло TTL: окно закрыто независимо от wall clock.
        time.advanceMonotonicBy(1L)
        assertTrue(token.isExpired(now = time.monotonicMillis()))
        assertFalse(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = time.monotonicMillis(),
                bootId = time.bootId,
            ),
        )
    }

    /** SEC-02: токен из прошлого запуска процесса не активируется. */
    @Test
    fun tokenFromPreviousBootIsRejected() {
        val time = FixedAgentTimeSource(monotonicMillis = 0L, wallMillis = 5_000L)
        val token = issueWith(time, ttlMs = 60_000L)

        assertTrue(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = time.monotonicMillis(),
                bootId = time.bootId,
            ),
        )

        assertEquals("boot-test", token.bootId)
        time.reboot()
        assertFalse(token.binding.bootIdMatches(time.bootId))
        assertFalse(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = time.monotonicMillis(),
                bootId = time.bootId,
            ),
        )
    }

    /**
     * SEC-01, главный тест: повторное предъявление того же токена обязано
     * упираться в реестр потребления. Тест падает, если guard убрать: binding
     * сам по себе остаётся валидным.
     */
    @Test
    fun presentedTwiceIsRejectedByConsumptionRegistry() {
        val time = FixedAgentTimeSource(monotonicMillis = 0L, wallMillis = 7_000L)
        val token = issueWith(time, ttlMs = 60_000L)
        val registry = ApprovalConsumptionRegistry(time)

        assertEquals(ApprovalState.ISSUED, registry.stateOf(token.value))

        val first = registry.consume(
            tokenValue = token.value,
            operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
            sessionId = token.sessionId,
            operationId = "op-1",
        )
        assertNotNull(first)
        assertEquals(ApprovalState.CONSUMED, registry.stateOf(token.value))

        // Binding всё ещё валиден — значит защита идёт именно из реестра.
        assertTrue(
            token.matches(
                presentedValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                workspaceId = token.workspaceId,
                workspaceFingerprint = token.workspaceFingerprint,
                path = token.path,
                oldSha256 = token.oldSha256,
                newSha256 = token.newSha256,
                argumentsJson = ARGUMENTS,
                now = time.monotonicMillis(),
                bootId = time.bootId,
            ),
        )
        assertNull(
            registry.consume(
                tokenValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
                operationId = "op-1",
            ),
        )
        assertEquals(1, registry.consumptionCount())
    }

    /** SEC-01: отозванный токен не потребляется и не активируется. */
    @Test
    fun revokedTokenIsNeverConsumable() {
        val time = FixedAgentTimeSource()
        val token = issueWith(time, ttlMs = 60_000L)
        val registry = ApprovalConsumptionRegistry(time)

        registry.revoke(token.value)

        assertEquals(ApprovalState.REVOKED, registry.stateOf(token.value))
        assertNull(
            registry.consume(
                tokenValue = token.value,
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = token.sessionId,
            ),
        )
        assertEquals(0, registry.consumptionCount())
    }

    /** SEC-01: пустое значение токена не потребляется. */
    @Test
    fun unknownTokenValueIsNotConsumed() {
        val registry = ApprovalConsumptionRegistry(FixedAgentTimeSource())

        assertNull(
            registry.consume(
                tokenValue = "   ",
                operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
                sessionId = "session-1",
            ),
        )
        assertEquals(ApprovalState.REVOKED, registry.stateOf(""))
        assertEquals(0, registry.consumptionCount())
    }

    private fun issue(now: Long, targetSha: String? = null): ApprovalToken {
        return ApprovalTokenFactory.issue(
            operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
            sessionId = "session-1",
            workspaceId = "workspace-1",
            workspaceFingerprint = "fingerprint-1",
            targetSha = targetSha,
            path = "src/App.kt",
            oldSha256 = "old-sha",
            newSha256 = "new-sha",
            argumentsJson = ARGUMENTS,
            issuedAt = now,
            ttlMs = 100L,
        )
    }

    private fun issueWith(
        time: FixedAgentTimeSource,
        ttlMs: Long,
    ): ApprovalToken {
        return ApprovalTokenFactory.issue(
            operation = ApprovalTokenFactory.APPLY_PATCH_OPERATION,
            sessionId = "session-1",
            workspaceId = "workspace-1",
            workspaceFingerprint = "fingerprint-1",
            path = "src/App.kt",
            oldSha256 = "old-sha",
            newSha256 = "new-sha",
            argumentsJson = ARGUMENTS,
            timeSource = time,
            ttlMs = ttlMs,
        )
    }

    private companion object {
        const val ARGUMENTS =
            """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}"""
    }
}
