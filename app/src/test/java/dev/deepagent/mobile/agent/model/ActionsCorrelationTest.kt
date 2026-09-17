package dev.deepagent.mobile.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ActionsCorrelationTest {

    @Test
    fun actionsRequestAuditCarriesOperationSessionAndCommit() {
        val request = ActionsRunRequest(
            repository = "owner/repo",
            workflow = "android.yml",
            ref = "main",
            sessionId = "session-1",
            operationId = "operation-1",
            expectedCommitSha = "a".repeat(40),
        )

        val audit = request.toAuditJson()

        assertEquals("session-1", audit.getString("session_id"))
        assertEquals("operation-1", audit.getString("operation_id"))
        assertEquals("a".repeat(40), audit.getString("expected_commit_sha"))
    }

    @Test
    fun actionsStateRoundTripsOperationCorrelation() {
        val state = ActionsOperationState(
            sessionId = "session-1",
            operationId = "operation-1",
            repository = "owner/repo",
            workflow = "android.yml",
            ref = "main",
            status = ActionsOperationStatus.SUCCEEDED,
            runId = 42L,
            headSha = "b".repeat(40),
        )

        val restored = ActionsOperationState.fromJson(state.toJson())

        assertNotNull(restored)
        assertEquals("session-1", restored.sessionId)
        assertEquals("operation-1", restored.operationId)
        assertEquals(42L, restored.runId)
        assertEquals("b".repeat(40), restored.headSha)
    }
}
