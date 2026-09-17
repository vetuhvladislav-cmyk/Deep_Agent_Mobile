package dev.deepagent.mobile.agent.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathPolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesOnlyExistingPublicWorkspaceFiles() {
        val root = temporaryFolder.newFolder("workspace")
        val source = File(root, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() = Unit")
        }

        assertEquals(source.canonicalFile, WorkspacePathPolicy.resolve(root, "src/Main.kt", true))
        assertEquals(
            root.canonicalFile,
            WorkspacePathPolicy.resolve(root, "", true),
        )
        assertTrue(
            WorkspacePathPolicy.resolve(root, "src/New.kt", false)
                .path
                .startsWith(root.canonicalPath),
        )
    }

    @Test
    fun rejectsTraversalAbsolutePathsAndSensitiveMetadata() {
        val root = temporaryFolder.newFolder("workspace")
        File(root, ".git/config").apply {
            parentFile?.mkdirs()
            writeText("[core]")
        }
        File(root, ".env").writeText("TOKEN=hidden")
        val outside = File(temporaryFolder.root, "outside.txt").apply {
            writeText("outside")
        }

        assertRejected { WorkspacePathPolicy.resolve(root, "../outside.txt", false) }
        assertRejected { WorkspacePathPolicy.resolve(root, outside.absolutePath, false) }
        assertRejected { WorkspacePathPolicy.resolve(root, ".git/config", true) }
        assertRejected { WorkspacePathPolicy.resolve(root, ".env", true) }
        assertTrue(WorkspacePathPolicy.isBlockedRelativePath("nested\\.GIT\\config"))
        assertTrue(WorkspacePathPolicy.isBlockedRelativePath("config/service-secret.json"))
    }

    @Test
    fun rejectsSymbolicLinksBeforeCanonicalResolution() {
        val root = temporaryFolder.newFolder("workspace")
        val outside = temporaryFolder.newFolder("outside")
        val link = File(root, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertRejected { WorkspacePathPolicy.resolve(root, "linked", true) }
        assertRejected { WorkspacePathPolicy.resolve(root, "linked/file.txt", false) }
    }

    private fun assertRejected(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("Expected path policy rejection", error)
    }
}
