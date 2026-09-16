package dev.harness.mobile.agent.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Минимальный локальный исполнитель для одного APK.
 *
 * Первый прототип намеренно запускает только диагностический probe. Полный
 * shell/PTY и тяжёлые Android toolchains подключаются отдельными слоями после
 * стабилизации AgentBridge.
 */
class LocalLiteRunner(context: Context) {

    private val workspace: File = File(context.filesDir, "agent-workspace")

    data class ProbeResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val workspace: File,
        val durationMs: Long,
    )

    suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        workspace.mkdirs()
        val startedAt = System.nanoTime()

        val process = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            "printf 'local-lite-ready\\n'; pwd; printf 'workspace=%s\\n' \"$workspace\"",
        )
            .directory(workspace)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
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
