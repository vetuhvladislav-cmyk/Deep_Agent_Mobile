package dev.deepagent.mobile.agent.runtime

import dev.deepagent.mobile.agent.model.InteractiveCommandRequest
import dev.deepagent.mobile.agent.model.InteractiveSessionState
import dev.deepagent.mobile.agent.model.InteractiveSessionStatus
import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Bounded interactive process adapter.
 *
 * It uses direct ProcessBuilder pipes, not a shell. The allowlist currently
 * contains read-only git commands; a real PTY backend must be added only after
 * the P2-A runtime ABI is explicitly approved.
 */
class InteractiveCommandSession(
    private val workspaceManager: WorkspaceManager,
) {
    private val processLock = Any()
    private val outputExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var activeSessionId: String? = null
    private var cancellationRequestedForSession: String? = null

    fun requiredPermission(request: InteractiveCommandRequest): PermissionMode {
        return if (
            request.executable == ALLOWED_EXECUTABLE &&
            request.args.firstOrNull() in READ_ONLY_VERBS
        ) {
            PermissionMode.READ_ONLY
        } else {
            PermissionMode.LOCAL_WRITE
        }
    }

    suspend fun run(
        request: InteractiveCommandRequest,
        onState: (InteractiveSessionState) -> Unit = {},
    ): InteractiveSessionState = withContext(Dispatchers.IO) {
        val sessionId = request.sessionId ?: java.util.UUID.randomUUID().toString()
        val base = InteractiveSessionState(
            sessionId = sessionId,
            workspaceId = request.workspaceId,
            executable = request.executable,
            args = request.args.take(MAX_ARGS),
            cwd = request.cwd,
        )
        val validationError = validate(request)
        if (validationError != null) {
            return@withContext publish(
                base.copy(
                    status = InteractiveSessionStatus.FAILED,
                    summary = validationError,
                    errorCode = "INTERACTIVE_INVALID_ARGUMENTS",
                ),
                onState,
            )
        }

        val cwd = workspaceManager.resolveRoot(request.workspaceId)
            ?.let { File(it, request.cwd.replace('\\', '/')).canonicalFile }
            ?: return@withContext publish(
                base.copy(
                    status = InteractiveSessionStatus.FAILED,
                    summary = "Workspace не выбран или недоступен",
                    errorCode = "INTERACTIVE_WORKSPACE_UNAVAILABLE",
                ),
                onState,
            )

        publish(
            base.copy(
                status = InteractiveSessionStatus.STARTING,
                summary = "Подготовка scoped interactive process",
            ),
            onState,
        )
        val startedAt = System.nanoTime()
        val process = try {
            ProcessBuilder(
                listOf(
                    ALLOWED_EXECUTABLE,
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "core.fsmonitor=false",
                ) + request.args,
            )
                .directory(cwd)
                .redirectErrorStream(false)
                .apply {
                    val environment = environment()
                    environment.clear()
                    environment["PATH"] = "/system/bin:/system/xbin"
                    environment["LANG"] = "C"
                    environment["LC_ALL"] = "C"
                    environment["TERM"] = "dumb"
                    environment["GIT_OPTIONAL_LOCKS"] = "0"
                    environment["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    request.env.entries
                        .filter { it.key in ALLOWED_ENV_KEYS }
                        .forEach { (key, value) ->
                            if (value.length <= MAX_ENV_VALUE_CHARS &&
                                value.none { it == '\u0000' }
                            ) {
                                environment[key] = value
                            }
                        }
                }
                .start()
        } catch (error: IOException) {
            return@withContext publish(
                base.copy(
                    status = InteractiveSessionStatus.FAILED,
                    summary = "Команда недоступна на устройстве",
                    errorCode = "INTERACTIVE_EXECUTABLE_UNAVAILABLE",
                ),
                onState,
            )
        }

        synchronized(processLock) {
            activeProcess = process
            activeSessionId = sessionId
            cancellationRequestedForSession = null
        }
        val stdoutFuture = outputExecutor.submit<CapturedOutput> {
            capture(process.inputStream, MAX_OUTPUT_CHARS)
        }
        val stderrFuture = outputExecutor.submit<CapturedOutput> {
            capture(process.errorStream, MAX_OUTPUT_CHARS)
        }

        try {
            publish(
                base.copy(
                    status = InteractiveSessionStatus.RUNNING,
                    summary = "Interactive process запущен",
                ),
                onState,
            )
            request.input?.let { input ->
                process.outputStream.write(input.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
                process.outputStream.close()
            }

            val finished = process.waitFor(
                request.timeoutMs.coerceIn(
                    MIN_TIMEOUT_MS,
                    MAX_TIMEOUT_MS,
                ),
                TimeUnit.MILLISECONDS,
            )
            currentCoroutineContext().ensureActive()
            if (isCancellationRequested(sessionId)) {
                terminate(process)
                return@withContext publish(
                    base.copy(
                        status = InteractiveSessionStatus.CANCELLED,
                        stdout = readOutput(stdoutFuture).text,
                        stderr = readOutput(stderrFuture).text,
                        durationMs = elapsedMs(startedAt),
                        summary = "Interactive process остановлен пользователем",
                    ),
                    onState,
                )
            }
            if (!finished) {
                terminate(process)
                return@withContext publish(
                    base.copy(
                        status = InteractiveSessionStatus.UNKNOWN,
                        stdout = readOutput(stdoutFuture).text,
                        stderr = readOutput(stderrFuture).text,
                        durationMs = elapsedMs(startedAt),
                        summary = "Interactive process превысил timeout",
                        errorCode = "INTERACTIVE_TIMEOUT",
                    ),
                    onState,
                )
            }

            val stdout = readOutput(stdoutFuture)
            val stderr = readOutput(stderrFuture)
            return@withContext publish(
                base.copy(
                    status = if (process.exitValue() == 0) {
                        InteractiveSessionStatus.SUCCEEDED
                    } else {
                        InteractiveSessionStatus.FAILED
                    },
                    stdout = stdout.text,
                    stderr = stderr.text,
                    exitCode = process.exitValue(),
                    durationMs = elapsedMs(startedAt),
                    summary = if (process.exitValue() == 0) {
                        "Interactive command завершён"
                    } else {
                        "Interactive command завершён с кодом " + process.exitValue()
                    },
                    errorCode = if (process.exitValue() == 0) null else "INTERACTIVE_EXIT_NONZERO",
                ),
                onState,
            )
        } catch (cancelled: CancellationException) {
            terminate(process)
            throw cancelled
        } finally {
            synchronized(processLock) {
                if (activeProcess === process) {
                    activeProcess = null
                    activeSessionId = null
                    cancellationRequestedForSession = null
                }
            }
        }
    }

    fun sendInput(sessionId: String, input: String): Boolean {
        require(input.length <= MAX_INPUT_CHARS && !input.contains('\u0000')) {
            "Interactive input превышает лимит"
        }
        val process = synchronized(processLock) {
            if (activeSessionId == sessionId) activeProcess else null
        } ?: return false
        return runCatching {
            process.outputStream.write(input.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            true
        }.getOrDefault(false)
    }

    fun cancelActive(sessionId: String? = null): Boolean {
        val process = synchronized(processLock) {
            if (
                activeProcess != null &&
                (sessionId == null || activeSessionId == sessionId)
            ) {
                cancellationRequestedForSession = activeSessionId
                activeProcess
            } else {
                null
            }
        } ?: return false
        terminate(process)
        return true
    }

    fun close() {
        cancelActive()
        outputExecutor.shutdownNow()
    }

    private fun validate(request: InteractiveCommandRequest): String? {
        if (request.executable != ALLOWED_EXECUTABLE) {
            return "Executable не входит в allowlist"
        }
        if (request.args.isEmpty() || request.args.size > MAX_ARGS) {
            return "Для interactive command нужен allowlisted subcommand"
        }
        if (request.args.first() !in READ_ONLY_VERBS) {
            return "Разрешены только read-only git status/diff/log"
        }
        val verb = request.args.first()
        var pathArgumentsStarted = false
        for (argument in request.args.drop(1)) {
            if (
                argument.isBlank() ||
                argument.length > MAX_ARG_CHARS ||
                argument.contains('\u0000')
            ) {
                return "Аргументы выходят за пределы interactive policy"
            }
            if (argument == "--") {
                if (pathArgumentsStarted) {
                    return "Аргументы выходят за пределы interactive policy"
                }
                pathArgumentsStarted = true
                continue
            }
            if (pathArgumentsStarted) {
                if (!isSafeRelativePath(argument)) {
                    return "Аргументы выходят за пределы interactive policy"
                }
                continue
            }
            if (argument !in ALLOWED_OPTIONS[verb].orEmpty()) {
                return "Опция не входит в read-only interactive allowlist"
            }
        }
        if (request.env.keys.any { it !in ALLOWED_ENV_KEYS }) {
            return "Environment key не входит в allowlist"
        }
        if (
            request.input != null &&
            (request.input.length > MAX_INPUT_CHARS ||
                request.input.contains('\u0000'))
        ) {
            return "Interactive input превышает лимит"
        }
        if (request.workspaceId.isNullOrBlank()) {
            return "Для interactive command нужен workspace"
        }
        val root = workspaceManager.resolveRoot(request.workspaceId)
            ?: return "Workspace не выбран или недоступен"
        if (request.cwd.isBlank() || request.cwd.contains('\u0000')) {
            return "cwd имеет недопустимый формат"
        }
        val normalizedCwd = request.cwd.replace('\\', '/')
        if (containsSymbolicLink(root, normalizedCwd)) {
            return "cwd содержит symbolic link"
        }
        val cwd = File(root, normalizedCwd).canonicalFile
        if (
            cwd.path != root.canonicalPath &&
            !cwd.path.startsWith(root.canonicalPath + File.separator)
        ) {
            return "cwd выходит за границы workspace"
        }
        if (!cwd.isDirectory || Files.isSymbolicLink(cwd.toPath())) {
            return "cwd не является безопасной директорией"
        }
        return null
    }

    private fun containsSymbolicLink(root: File, relativePath: String): Boolean {
        var cursor = root.canonicalFile.toPath()
        relativePath.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> cursor = cursor.parent ?: cursor
                else -> {
                    cursor = cursor.resolve(part)
                    if (Files.isSymbolicLink(cursor)) return true
                }
            }
        }
        return false
    }

    private fun isSafeRelativePath(value: String): Boolean {
        val normalized = value.replace('\\', '/')
        return !normalized.startsWith("/") &&
            normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun publish(
        state: InteractiveSessionState,
        onState: (InteractiveSessionState) -> Unit,
    ): InteractiveSessionState {
        val next = state.copy(
            stdout = AgentRedactor.text(state.stdout, MAX_OUTPUT_CHARS),
            stderr = AgentRedactor.text(state.stderr, MAX_OUTPUT_CHARS),
            summary = AgentRedactor.text(state.summary, MAX_SUMMARY_CHARS),
            errorCode = AgentRedactor.text(state.errorCode, MAX_ERROR_CODE_CHARS),
            updatedAt = System.currentTimeMillis(),
        )
        try {
            onState(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A diagnostic UI callback must not change process state.
        }
        return next
    }

    private fun readOutput(future: Future<CapturedOutput>): CapturedOutput =
        runCatching { future.get(2, TimeUnit.SECONDS) }
            .getOrElse { CapturedOutput("", true) }

    private fun isCancellationRequested(sessionId: String): Boolean =
        synchronized(processLock) {
            cancellationRequestedForSession == sessionId
        }

    private fun capture(input: java.io.InputStream, maxChars: Int): CapturedOutput {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = maxChars - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                output.write(buffer, 0, minOf(count, remaining))
                if (count > remaining) truncated = true
            }
        }
        return CapturedOutput(
            text = AgentRedactor.text(
                output.toByteArray().toString(Charsets.UTF_8),
                maxChars,
            ).orEmpty(),
            truncated = truncated,
        )
    }

    private fun terminate(process: Process) {
        runCatching {
            process.outputStream.close()
            process.destroy()
            if (!process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private data class CapturedOutput(
        val text: String,
        val truncated: Boolean,
    )

    private companion object {
        const val ALLOWED_EXECUTABLE = "git"
        val READ_ONLY_VERBS = setOf("status", "diff", "log")
        val ALLOWED_OPTIONS = mapOf(
            "status" to setOf(
                "--short",
                "--branch",
                "--porcelain",
                "--untracked-files=all",
                "--no-color",
            ),
            "diff" to setOf(
                "--no-ext-diff",
                "--no-textconv",
                "--no-renames",
                "--stat",
                "--name-only",
                "--name-status",
                "--no-color",
            ),
            "log" to setOf(
                "--oneline",
                "--decorate",
                "--stat",
                "--no-color",
            ),
        )
        val ALLOWED_ENV_KEYS = setOf("LANG", "LC_ALL", "TERM")
        const val MAX_ARGS = 32
        const val MAX_ARG_CHARS = 256
        const val MAX_ENV_VALUE_CHARS = 128
        const val MAX_INPUT_CHARS = 8_000
        const val MAX_OUTPUT_CHARS = 32_000
        const val MAX_ERROR_CODE_CHARS = 96
        const val MAX_SUMMARY_CHARS = 2_000
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val TERMINATE_GRACE_MS = 1_000L
    }
}
