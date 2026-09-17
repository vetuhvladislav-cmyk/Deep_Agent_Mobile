package dev.deepagent.mobile.agent.runtime

import android.content.Context
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
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
            .redirectErrorStream(true)
            .start()

        val executor = Executors.newSingleThreadExecutor()
        val capture = executor.submit<String> {
            captureOutput(process, MAX_OUTPUT_CHARS)
        }
        val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = runCatching {
            capture.get(CAPTURE_GRACE_SECONDS, TimeUnit.SECONDS)
        }.getOrElse {
            process.destroyForcibly()
            capture.cancel(true)
            ""
        }
        executor.shutdownNow()

        if (!finished) {
            throw IllegalStateException("Local Lite Runner probe timeout")
        }

        ProbeResult(
            exitCode = process.exitValue(),
            stdout = (output + "workspace=" + workspace.path).trim(),
            stderr = "",
            workspace = workspace,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private fun captureOutput(process: Process, maxChars: Int): String {
        val output = StringBuilder(maxChars.coerceAtMost(4 * 1024))
        val buffer = CharArray(4 * 1024)
        var remaining = maxChars.coerceAtLeast(0)
        process.inputStream.bufferedReader().use { reader ->
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (remaining > 0) {
                    val kept = minOf(count, remaining)
                    output.append(buffer, 0, kept)
                    remaining -= kept
                }
            }
        }
        return output.toString()
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 10L
        const val CAPTURE_GRACE_SECONDS = 2L
        const val MAX_OUTPUT_CHARS = 8_000
    }

}
