package dev.deepagent.mobile.agent.patch

import dev.deepagent.mobile.agent.workspace.WorkspaceIdentity
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PatchEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun previewsUnifiedPatchAndRollsBackWithCheckpoint() {
        val workspace = temporaryFolder.newFolder("workspace")
        val target = File(workspace, "src/App.kt").apply {
            parentFile?.mkdirs()
            writeText("one\ntwo\n")
        }
        val engine = PatchEngine(temporaryFolder.newFolder("checkpoints"))
        val arguments = JSONObject(
            """{"path":"src/App.kt","expected_sha256":"${sha256(target.readBytes())}","patch":"@@ -1,2 +1,2 @@\n one\n-two\n+three"}""",
        )

        val preview = engine.preview(workspace, arguments)
        assertEquals("one\nthree\n", preview.after)
        assertEquals("src/App.kt", preview.path)
        assertTrue(preview.unifiedDiff.contains("-two"))
        assertTrue(preview.unifiedDiff.contains("+three"))

        val applied = engine.apply(
            workspaceRoot = workspace,
            arguments = arguments,
            expectedWorkspaceFingerprint = preview.workspaceFingerprint,
        )
        assertEquals("one\nthree\n", target.readText())

        val afterFingerprint = WorkspaceIdentity.capture("test", workspace).treeSha256
        val checkpoint = engine.markCheckpointApplied(
            operationId = applied.operationId,
            workspaceFingerprintAfter = afterFingerprint,
        )
        assertEquals(PatchCheckpointStatus.APPLIED, checkpoint.status)

        val rollback = engine.rollback(
            workspaceRoot = workspace,
            operationId = applied.operationId,
            expectedWorkspaceFingerprint = afterFingerprint,
        )
        assertEquals(PatchRollbackStatus.SUCCEEDED, rollback.status)
        assertEquals("one\ntwo\n", target.readText())
    }

    @Test
    fun applyRejectsWorkspaceChangedAfterPreview() {
        val workspace = temporaryFolder.newFolder("workspace")
        val target = File(workspace, "App.kt").apply {
            writeText("before\n")
        }
        val engine = PatchEngine(temporaryFolder.newFolder("checkpoints"))
        val arguments = JSONObject(
            """{"path":"App.kt","expected_sha256":"$expectedSha","replacement":"after\n"}""",
        )
        val preview = engine.preview(workspace, arguments)
        target.writeText("changed\n")

        var rejected = false
        try {
            engine.apply(workspace, arguments, preview.workspaceFingerprint)
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Изменившийся workspace должен остановить apply", rejected)
    }

    @Test
    fun parserRejectsMalformedOrConflictingPatch() {
        val workspace = temporaryFolder.newFolder("workspace")
        val target = File(workspace, "App.kt").apply {
            writeText("before\n")
        }
        val engine = PatchEngine(temporaryFolder.newFolder("checkpoints"))
        val expectedSha = sha256(target.readBytes())

        assertRejected(
            engine,
            workspace,
            JSONObject(
                """{"path":"App.kt","expected_sha256":"$expectedSha","replacement":"after\n","patch":"@@ -1 +1 @@\n-before\n+after"}""",
            ),
        )
        assertRejected(
            engine,
            workspace,
            JSONObject(
                """{"path":"App.kt","expected_sha256":"$expectedSha","patch":"@@ -1,1 +1,1 @@\n-wrong\n+after"}""",
            ),
        )
    }

    private fun assertRejected(
        engine: PatchEngine,
        workspace: File,
        arguments: JSONObject,
    ) {
        var rejected = false
        try {
            engine.preview(workspace, arguments)
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Некорректный patch должен быть отклонён", rejected)
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
