package dev.deepagent.mobile.agent.session

import android.content.Context
import android.content.ContextWrapper
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesVersionedRedactedSnapshotAndRestoresLatestSession() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val session = sampleSession(
            status = AgentSessionStatus.RUNNING,
            updatedAt = 100L,
        )

        SessionStore(TestContext(filesDirectory)).save(session)

        val journalDirectory = File(filesDirectory, "agent-sessions")
        val persistedFile = File(
            journalDirectory,
            "session-" + session.sessionId + ".json",
        )
        assertTrue(persistedFile.isFile)
        assertTrue(File(journalDirectory, "latest").isFile)
        assertTrue(
            journalDirectory.listFiles()?.none { it.name.endsWith(".tmp") } == true,
        )

        val json = JSONObject(persistedFile.readText())
        assertEquals(PersistedAgentSession.VERSION, json.getInt("schema_version"))
        assertEquals(
            session.sessionId,
            SessionStore(TestContext(filesDirectory)).loadLatest()?.sessionId,
        )

        val restored = SessionStore(TestContext(filesDirectory)).load(session.sessionId)
        assertNotNull(restored)
        assertEquals(session.eventCursor, restored?.eventCursor)
        assertEquals(1, restored?.events?.size)
        assertEquals(1, restored?.invocations?.size)
        assertEquals(1, restored?.decisions?.size)
        assertEquals(
            AgentEvent.SCHEMA_VERSION,
            restored?.events?.single()?.schemaVersion,
        )
        assertFalse(
            restored?.events?.single()?.detail?.contains("ghp_secret_token_123456") == true,
        )
        assertFalse(
            restored?.events?.single()?.payload?.contains("ghp_secret_token_123456") == true,
        )
    }

    @Test
    fun invalidPointerAndUnsupportedSnapshotsDoNotReplaceValidLatest() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val valid = sampleSession(
            sessionId = "session-valid",
            status = AgentSessionStatus.COMPLETED,
            updatedAt = 200L,
        )
        SessionStore(TestContext(filesDirectory)).save(valid)

        val journalDirectory = File(filesDirectory, "agent-sessions")
        File(journalDirectory, "latest").writeText("../outside")
        File(journalDirectory, "session-unsupported.json").writeText(
            """{"schema_version":999,"session_id":"session-unsupported"}""",
        )

        val restored = SessionStore(TestContext(filesDirectory)).loadLatest()
        assertNotNull(restored)
        assertEquals(valid.sessionId, restored?.sessionId)
        assertEquals(AgentSessionStatus.COMPLETED, restored?.state?.status)
    }

    private fun sampleSession(
        sessionId: String = "session-1",
        status: AgentSessionStatus,
        updatedAt: Long,
    ): PersistedAgentSession {
        val event = AgentEvent(
            kind = AgentEventKind.TOOL,
            message = "read_file completed",
            detail = "Authorization: Bearer ghp_secret_token_123456",
            sessionId = sessionId,
            schemaVersion = AgentEvent.SCHEMA_VERSION,
            payload = """{"operation":"read_file","token":"ghp_secret_token_123456"}""",
            eventId = sessionId + ":event-1",
            sequence = 1L,
        )
        return PersistedAgentSession(
            sessionId = sessionId,
            request = SessionRequestSummary(
                task = "Inspect workspace",
                target = ExecutionTarget.LOCAL_LITE,
                permission = PermissionMode.READ_ONLY,
                workspaceId = "workspace-1",
                repository = null,
                workflow = null,
                ref = "main",
            ),
            state = AgentSessionState(
                status = status,
                target = ExecutionTarget.LOCAL_LITE,
                task = "Inspect workspace",
                sessionId = sessionId,
                eventCursor = 1L,
            ),
            events = listOf(event),
            updatedAt = updatedAt,
            eventCursor = 1L,
            invocations = listOf(
                SessionInvocationRecord(
                    invocationId = sessionId + ":invocation-1",
                    toolName = "read_file",
                    state = "RUNNING",
                ),
            ),
            decisions = listOf(
                SessionDecisionRecord(
                    decisionId = sessionId + ":decision-1",
                    kind = "SESSION",
                    state = "STARTED",
                ),
            ),
        )
    }

    private class TestContext(
        private val filesDirectory: File,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = filesDirectory
    }
}
