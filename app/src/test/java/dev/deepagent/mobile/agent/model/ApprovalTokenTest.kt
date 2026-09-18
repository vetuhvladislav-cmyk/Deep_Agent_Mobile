package dev.deepagent.mobile.agent.model

import org.junit.Assert.assertFalse
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
                argumentsJson = """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
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
                argumentsJson = """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
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
                argumentsJson = """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
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
                argumentsJson = """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
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
                argumentsJson = """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
                now = 30_001L,
            ),
        )
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
            argumentsJson = """{"path":"src/App.kt","patch":"@@ -1 +1 @@\n-old\n+new"}""",
            now = now,
            ttlMs = 100L,
        )
    }
}
