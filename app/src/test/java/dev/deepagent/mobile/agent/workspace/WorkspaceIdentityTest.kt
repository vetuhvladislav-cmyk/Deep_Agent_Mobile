package dev.deepagent.mobile.agent.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceIdentityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fingerprintIsDeterministicAndChangesWithContent() {
        val root = temporaryFolder.newFolder("workspace")
        File(root, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() = Unit")
        }
        File(root, "README.md").writeText("Deep Agent")

        val first = WorkspaceIdentity.capture("workspace-1", root)
        val second = WorkspaceIdentity.capture("workspace-1", root)

        assertEquals(first.treeSha256, second.treeSha256)
        assertEquals(2, first.fileCount)
        assertEquals(
            File(root, "src/Main.kt").length() + File(root, "README.md").length(),
            first.totalBytes,
        )

        File(root, "README.md").appendText(" changed")
        val changed = WorkspaceIdentity.capture("workspace-1", root)
        assertNotEquals(first.treeSha256, changed.treeSha256)
    }

    @Test
    fun fingerprintFailsClosedOnSymbolicLinks() {
        val root = temporaryFolder.newFolder("workspace")
        val outside = temporaryFolder.newFolder("outside")
        val link = File(root, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        val error = runCatching {
            WorkspaceIdentity.capture("workspace-1", root)
        }.exceptionOrNull()

        assertNotNull("Workspace identity must reject symbolic links", error)
    }
}
