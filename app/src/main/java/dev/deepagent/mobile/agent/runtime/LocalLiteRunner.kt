package dev.deepagent.mobile.agent.runtime

import android.content.Context
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal local probe.
 *
 * It executes a fixed diagnostic command only; user or model text is never
 * interpolated into a shell command. If a workspace is selected, the probe
 * runs from that read-only imported snapshot.
 */
class LocalLiteRunner(
    context: Context,
    private val workspaceManager: WorkspaceManager,
) {

    private val fallbackWorkspace: File = File(
        context.applicationContext.filesDir,
        "agent-workspace",
    )

    data class ProbeResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val workspace: File,
        val durationMs: Long,
    )

    suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val workspace = workspaceManager.resolveRoot() ?: fallbackWorkspace
        workspace.mkdirs()
        val startedAt = System.nanoTime()

        val process = ProcessBuilder(
            "/system/bin/printf",
            "local-lite-ready\\n",
        )
            .directory(workspace)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() } +
            "workspace=" + workspace.path
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(10, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Local Lite Runner probe timeout")
        }

        ProbeResult(
            exitCode = process.exitValue(),
            stdout = stdout.trim(),
            stderr = stderr.trim(),
            workspace = workspace,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }
}
