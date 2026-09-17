package dev.deepagent.mobile.agent.tools

import android.content.Context
import android.content.ContextWrapper
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolRouterReadOnlyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspaceId: String
    private lateinit var workspaceRoot: File
    private lateinit var router: ToolRouter

    @Before
    fun setUp() {
        val filesDirectory = temporaryFolder.newFolder("files")
        workspaceId = "workspace-1"
        workspaceRoot = File(filesDirectory, "agent-workspaces/$workspaceId").apply {
            mkdirs()
        }
        File(workspaceRoot, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() = println(\"deep agent\")")
        }
        File(workspaceRoot, "README.md").writeText("safe workspace")
        File(workspaceRoot, ".env").writeText("TOKEN=must-not-leak")

        val escapedRootPath = workspaceRoot.absolutePath
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val totalBytes = workspaceRoot.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        val index = """
            {
              "version": 2,
              "selected_id": "$workspaceId",
              "workspaces": [{
                "id": "$workspaceId",
                "display_name": "Fixture",
                "source_type": "test",
                "root_path": "$escapedRootPath",
                "file_count": 3,
                "total_bytes": $totalBytes,
                "imported_at": 1
              }]
            }
        """.trimIndent()
        File(filesDirectory, "agent-workspaces.json").writeText(index)

        router = ToolRouter(WorkspaceManager(TestContext(filesDirectory)))
    }

    @Test
    fun exposesBoundedReadOnlyWorkspaceOperations() = runBlocking {
        val listed = router.execute(
            ToolRouter.TOOL_LIST_FILES,
            """{"path":"","max_depth":4}""",
            workspaceId,
        )
        assertTrue(listed.ok)
        assertTrue(listed.content.contains("src/Main.kt"))
        assertFalse(listed.content.contains(".env"))

        val read = router.execute(
            ToolRouter.TOOL_READ_FILE,
            """{"path":"src/Main.kt"}""",
            workspaceId,
        )
        assertTrue(read.ok)
        assertTrue(read.content.contains("deep agent"))

        val search = router.execute(
            ToolRouter.TOOL_SEARCH_CODE,
            """{"query":"deep agent"}""",
            workspaceId,
        )
        assertTrue(search.ok)
        assertTrue(search.content.contains("src/Main.kt"))
    }

    @Test
    fun rejectsUnknownArgumentsAndWorkspaceEscapes() = runBlocking {
        val unknown = router.execute(
            ToolRouter.TOOL_READ_FILE,
            """{"path":"README.md","extra":"reject-me"}""",
            workspaceId,
        )
        assertFalse(unknown.ok)
        assertEquals("INVALID_ARGUMENTS", unknown.errorCode)

        val escaped = router.execute(
            ToolRouter.TOOL_READ_FILE,
            """{"path":"../outside.txt"}""",
            workspaceId,
        )
        assertFalse(escaped.ok)

        val sensitive = router.execute(
            ToolRouter.TOOL_READ_FILE,
            """{"path":".env"}""",
            workspaceId,
        )
        assertFalse(sensitive.ok)
    }

    @Test
    fun gitToolsFailClosedWhenWorkspaceIsNotRepository() = runBlocking {
        val status = router.execute(ToolRouter.TOOL_GIT_STATUS, "{}", workspaceId)
        assertFalse(status.ok)
        assertEquals("NOT_A_GIT_REPOSITORY", status.errorCode)

        val diff = router.execute(ToolRouter.TOOL_GIT_DIFF, "{}", workspaceId)
        assertFalse(diff.ok)
        assertEquals("NOT_A_GIT_REPOSITORY", diff.errorCode)
    }

    @Test
    fun definitionsDeclareStrictBoundedArguments() {
        val definitions = ToolRouter.definitions().associateBy { it.name }

        assertEquals(6, definitions.size)
        assertTrue(definitions.values.all {
            !it.parameters.optBoolean("additionalProperties", true)
        })

        val listPath = definitions.getValue(ToolRouter.TOOL_LIST_FILES)
            .parameters.getJSONObject("properties")
            .getJSONObject("path")
        assertEquals(512, listPath.getInt("maxLength"))

        val readBytes = definitions.getValue(ToolRouter.TOOL_READ_FILE)
            .parameters.getJSONObject("properties")
            .getJSONObject("max_bytes")
        assertEquals(ToolRouter.MAX_READ_BYTES, readBytes.getInt("maximum"))

        val searchQuery = definitions.getValue(ToolRouter.TOOL_SEARCH_CODE)
            .parameters.getJSONObject("properties")
            .getJSONObject("query")
        assertEquals(ToolRouter.MAX_QUERY_LENGTH, searchQuery.getInt("maxLength"))
    }

    private class TestContext(
        private val filesDirectory: File,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = filesDirectory
    }
}
