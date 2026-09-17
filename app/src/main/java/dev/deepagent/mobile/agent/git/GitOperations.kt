package dev.deepagent.mobile.agent.git

import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import dev.deepagent.mobile.agent.workspace.WorkspacePathPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class GitOperation {
    STATUS,
    CREATE_BRANCH,
    COMMIT,
    PUSH,
    CREATE_PULL_REQUEST,
}

enum class GitOperationStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

data class GitOperationState(
    val status: GitOperationStatus = GitOperationStatus.IDLE,
    val sessionId: String? = null,
    val repository: String? = null,
    val base: String? = null,
    val expectedHeadSha: String? = null,
    val operation: String? = null,
    val summary: String? = null,
    val branch: String? = null,
    val headSha: String? = null,
    val workspaceFingerprintBefore: String? = null,
    val workspaceFingerprintAfter: String? = null,
    val errorCode: String? = null,
    val pullRequestNumber: Int? = null,
    val pullRequestUrl: String? = null,
    val updatedAt: Long? = null,
)

data class GitOperationResult(
    val operation: GitOperation,
    val sessionId: String? = null,
    val repository: String? = null,
    val base: String? = null,
    val expectedHeadSha: String? = null,
    val status: GitOperationStatus,
    val summary: String,
    val content: String = "",
    val errorCode: String? = null,
    val branch: String? = null,
    val headSha: String? = null,
    val workspaceFingerprintBefore: String? = null,
    val workspaceFingerprintAfter: String? = null,
    val pullRequestNumber: Int? = null,
    val pullRequestUrl: String? = null,
    val truncated: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("operation", operation.name)
        .put("session_id", AgentRedactor.text(sessionId, MAX_IDENTIFIER_CHARS))
        .put("repository", AgentRedactor.text(repository, MAX_IDENTIFIER_CHARS))
        .put("base", AgentRedactor.text(base, MAX_IDENTIFIER_CHARS))
        .put("expected_head_sha", AgentRedactor.text(expectedHeadSha, MAX_IDENTIFIER_CHARS))
        .put("status", status.name)
        .put("summary", AgentRedactor.text(summary, MAX_SUMMARY_CHARS))
        .put("content", AgentRedactor.text(content, MAX_CONTENT_CHARS))
        .put("error_code", errorCode)
        .put("branch", AgentRedactor.text(branch, MAX_IDENTIFIER_CHARS))
        .put("head_sha", AgentRedactor.text(headSha, MAX_IDENTIFIER_CHARS))
        .put(
            "workspace_fingerprint_before",
            AgentRedactor.text(workspaceFingerprintBefore, MAX_IDENTIFIER_CHARS),
        )
        .put(
            "workspace_fingerprint_after",
            AgentRedactor.text(workspaceFingerprintAfter, MAX_IDENTIFIER_CHARS),
        )
        .put("pull_request_number", pullRequestNumber)
        .put(
            "pull_request_url",
            AgentRedactor.text(pullRequestUrl, MAX_IDENTIFIER_CHARS),
        )
        .put("truncated", truncated)

    private companion object {
        const val MAX_SUMMARY_CHARS = 2_000
        const val MAX_CONTENT_CHARS = 64_000
        const val MAX_IDENTIFIER_CHARS = 200
    }
}

data class GitBranchRequest(
    val name: String,
    val startPoint: String? = null,
)

data class GitCommitRequest(
    val paths: List<String>,
    val message: String,
)

data class GitPushRequest(
    val remote: String = "origin",
    val branch: String,
)

data class GitPullRequestRequest(
    val repository: String,
    val head: String,
    val base: String = "main",
    val title: String,
    val body: String = "",
    val draft: Boolean = true,
    val expectedHeadSha: String? = null,
    val sessionId: String? = null,
)

sealed interface GitHubPullRequestResult {
    data class Created(
        val number: Int,
        val url: String,
        val title: String,
        val head: String,
        val base: String,
        val headSha: String?,
    ) : GitHubPullRequestResult

    data class Failed(
        val message: String,
        val errorCode: String,
    ) : GitHubPullRequestResult
}

/**
 * Fixed-argument local Git gateway.
 *
 * The gateway never invokes a shell and never accepts an arbitrary command.
 * Write methods are called only by AgentCore after the AgentBridge approval
 * gate. A successful write is not reported until the post-operation workspace
 * fingerprint and HEAD are both available.
 */
class GitRepositoryClient(
    private val workspaceManager: WorkspaceManager,
) {

    suspend fun status(workspaceId: String?): GitOperationResult =
        withContext(Dispatchers.IO) {
            val root = workspaceRoot(workspaceId)
                ?: return@withContext unavailable(
                    GitOperation.STATUS,
                    "Workspace не выбран или недоступен",
                    "WORKSPACE_UNAVAILABLE",
                )
            if (!isGitRepository(root)) {
                return@withContext unavailable(
                    GitOperation.STATUS,
                    "Workspace не содержит .git",
                    "NOT_A_GIT_REPOSITORY",
                )
            }

            val command = runGit(
                root,
                listOf(
                    "status",
                    "--short",
                    "--branch",
                    "--untracked-files=all",
                ),
            )
            val head = readHead(root)
            val status = when {
                command.startError != null -> GitOperationStatus.FAILED
                command.timedOut -> GitOperationStatus.UNKNOWN
                command.exitCode == 0 -> GitOperationStatus.SUCCEEDED
                else -> GitOperationStatus.FAILED
            }
            GitOperationResult(
                operation = GitOperation.STATUS,
                status = status,
                summary = when (status) {
                    GitOperationStatus.SUCCEEDED -> "Git status получен"
                    GitOperationStatus.UNKNOWN -> "Git status не подтверждён; требуется re-check"
                    else -> "Git status завершился с ошибкой"
                },
                content = safeText(
                    command.startError ?: command.output,
                    MAX_COMMAND_OUTPUT_CHARS,
                ),
                errorCode = when {
                    command.startError != null -> "GIT_UNAVAILABLE"
                    command.timedOut -> "GIT_COMMAND_UNKNOWN"
                    command.exitCode == 0 -> null
                    else -> "GIT_STATUS_FAILED"
                },
                branch = head?.branch,
                headSha = head?.sha,
                truncated = command.truncated,
            )
        }

    suspend fun createBranch(
        workspaceId: String?,
        request: GitBranchRequest,
    ): GitOperationResult = withContext(Dispatchers.IO) {
        val root = requireGitWorkspace(workspaceId)
            ?: return@withContext unavailable(
                GitOperation.CREATE_BRANCH,
                "Workspace не выбран или недоступен",
                "WORKSPACE_UNAVAILABLE",
            )
        val branch = validateRef(request.name, "branch")
        val startPoint = request.startPoint
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { validateRef(it, "start point") }
        val before = captureFingerprint(workspaceId)
            ?: return@withContext unavailable(
                GitOperation.CREATE_BRANCH,
                "Workspace fingerprint недоступен; требуется re-check",
                "WORKSPACE_RECHECK_REQUIRED",
            )

        val args = mutableListOf("checkout", "-b", branch)
        startPoint?.let(args::add)
        val command = runGit(root, args)
        val after = captureFingerprint(workspaceId)
        val head = readHead(root)
        val status = writeStatus(command, after, head)
        GitOperationResult(
            operation = GitOperation.CREATE_BRANCH,
            status = status,
            summary = when (status) {
                GitOperationStatus.SUCCEEDED -> "Git branch создан: " + branch
                GitOperationStatus.UNKNOWN -> "Создание branch не подтверждено; требуется re-check"
                else -> "Не удалось создать branch: " + branch
            },
            content = safeText(
                command.startError ?: command.output,
                MAX_COMMAND_OUTPUT_CHARS,
            ),
            errorCode = writeErrorCode(command, status, "GIT_BRANCH_FAILED"),
            branch = head?.branch ?: branch,
            headSha = head?.sha,
            workspaceFingerprintBefore = before,
            workspaceFingerprintAfter = after,
            truncated = command.truncated,
        )
    }

    suspend fun commit(
        workspaceId: String?,
        request: GitCommitRequest,
    ): GitOperationResult = withContext(Dispatchers.IO) {
        val root = requireGitWorkspace(workspaceId)
            ?: return@withContext unavailable(
                GitOperation.COMMIT,
                "Workspace не выбран или недоступен",
                "WORKSPACE_UNAVAILABLE",
            )
        val paths = validatePaths(root, request.paths)
        val message = request.message.trim()
        require(message.isNotBlank()) { "Commit message не может быть пустым" }
        require(message.length <= MAX_COMMIT_MESSAGE_CHARS) {
            "Commit message превышает лимит"
        }
        val before = captureFingerprint(workspaceId)
            ?: return@withContext unavailable(
                GitOperation.COMMIT,
                "Workspace fingerprint недоступен; требуется re-check",
                "WORKSPACE_RECHECK_REQUIRED",
            )

        val addCommand = runGit(root, listOf("add", "--") + paths)
        if (addCommand.startError != null || addCommand.timedOut || addCommand.exitCode != 0) {
            val after = captureFingerprint(workspaceId)
            return@withContext GitOperationResult(
                operation = GitOperation.COMMIT,
                status = GitOperationStatus.UNKNOWN,
                summary = "Индекс Git изменён или не подтверждён; commit требует re-check",
                content = safeText(
                    addCommand.startError ?: addCommand.output,
                    MAX_COMMAND_OUTPUT_CHARS,
                ),
                errorCode = if (addCommand.startError != null) {
                    "GIT_UNAVAILABLE"
                } else {
                    "GIT_STAGE_UNKNOWN"
                },
                branch = readHead(root)?.branch,
                headSha = readHead(root)?.sha,
                workspaceFingerprintBefore = before,
                workspaceFingerprintAfter = after,
                truncated = addCommand.truncated,
            )
        }

        val commitCommand = runGit(
            root,
            listOf("commit", "--no-verify", "--only", "-m", message, "--") + paths,
        )
        val after = captureFingerprint(workspaceId)
        val head = readHead(root)
        val status = writeStatus(commitCommand, after, head)
        GitOperationResult(
            operation = GitOperation.COMMIT,
            status = status,
            summary = when (status) {
                GitOperationStatus.SUCCEEDED -> "Commit создан: " + head?.sha.orEmpty()
                GitOperationStatus.UNKNOWN -> "Commit не подтверждён; staging/commit требует re-check"
                else -> "Commit завершился с ошибкой"
            },
            content = safeText(
                commitCommand.startError ?: commitCommand.output,
                MAX_COMMAND_OUTPUT_CHARS,
            ),
            errorCode = writeErrorCode(commitCommand, status, "GIT_COMMIT_FAILED"),
            branch = head?.branch,
            headSha = head?.sha,
            workspaceFingerprintBefore = before,
            workspaceFingerprintAfter = after,
            truncated = commitCommand.truncated,
        )
    }

    suspend fun push(
        workspaceId: String?,
        request: GitPushRequest,
    ): GitOperationResult = withContext(Dispatchers.IO) {
        val root = requireGitWorkspace(workspaceId)
            ?: return@withContext unavailable(
                GitOperation.PUSH,
                "Workspace не выбран или недоступен",
                "WORKSPACE_UNAVAILABLE",
            )
        val remote = validateRemote(request.remote)
        val branch = validateRef(request.branch, "branch")
        val before = captureFingerprint(workspaceId)
            ?: return@withContext unavailable(
                GitOperation.PUSH,
                "Workspace fingerprint недоступен; требуется re-check",
                "WORKSPACE_RECHECK_REQUIRED",
            )

        val command = runGit(
            root,
            listOf(
                "push",
                "--no-verify",
                "--porcelain",
                "--set-upstream",
                remote,
                branch,
            ),
        )
        val after = captureFingerprint(workspaceId)
        val head = readHead(root)
        val status = writeStatus(command, after, head)
        GitOperationResult(
            operation = GitOperation.PUSH,
            status = status,
            summary = when (status) {
                GitOperationStatus.SUCCEEDED -> "Push подтверждён: " + remote + "/" + branch
                GitOperationStatus.UNKNOWN -> "Push не подтверждён; повторять нельзя до re-check"
                else -> "Push завершился с ошибкой"
            },
            content = safeText(
                command.startError ?: command.output,
                MAX_COMMAND_OUTPUT_CHARS,
            ),
            errorCode = writeErrorCode(command, status, "GIT_PUSH_FAILED"),
            branch = head?.branch ?: branch,
            headSha = head?.sha,
            workspaceFingerprintBefore = before,
            workspaceFingerprintAfter = after,
            truncated = command.truncated,
        )
    }

    private fun requireGitWorkspace(workspaceId: String?): File? {
        val root = workspaceRoot(workspaceId)
        if (root == null || !isGitRepository(root)) return null
        return root
    }

    private fun workspaceRoot(workspaceId: String?): File? {
        return workspaceManager.resolveRoot(workspaceId)
    }

    private fun captureFingerprint(workspaceId: String?): String? {
        return runCatching {
            workspaceManager.captureIdentity(workspaceId)?.treeSha256
        }.getOrNull()
    }

    private fun isGitRepository(root: File): Boolean {
        val metadata = File(root, ".git")
        return metadata.isDirectory && !Files.isSymbolicLink(metadata.toPath())
    }

    private fun validatePaths(root: File, paths: List<String>): List<String> {
        require(paths.isNotEmpty()) { "Для commit нужен хотя бы один path" }
        val normalized = paths.map { path ->
            val value = path.trim().replace('\\', '/')
            require(value.isNotBlank()) { "Commit path не может быть пустым" }
            require(!value.startsWith("/") && !value.contains('\u0000')) {
                "Недопустимый commit path"
            }
            val resolved = WorkspacePathPolicy.resolve(
                root = root,
                requestedPath = value,
                requireExisting = false,
            )
            require(!resolved.exists() || resolved.isFile) {
                "Commit path должен указывать на файл"
            }
            value
        }.distinct()
        require(normalized.isNotEmpty()) { "Для commit нужен хотя бы один path" }
        return normalized
    }

    private fun validateRef(value: String, label: String): String {
        val ref = value.trim()
        require(ref.length in 1..160) { label + " имеет недопустимую длину" }
        require(
            ref.first() != '-' &&
                !ref.startsWith("/") &&
                !ref.endsWith("/") &&
                !ref.endsWith(".") &&
                !ref.contains("..") &&
                !ref.contains("@{") &&
                !ref.contains("//"),
        ) {
            label + " имеет недопустимый формат"
        }
        require(
            ref.all { char ->
                char.isLetterOrDigit() || char in "._/-"
            },
        ) {
            label + " содержит недопустимый символ"
        }
        return ref
    }

    private fun validateRemote(value: String): String {
        val remote = value.trim()
        require(remote.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "Remote имеет недопустимый формат"
        }
        return remote
    }

    private fun readHead(root: File): HeadSnapshot? {
        val branchCommand = runGit(
            root,
            listOf("symbolic-ref", "--quiet", "--short", "HEAD"),
        )
        val headCommand = runGit(
            root,
            listOf("rev-parse", "--verify", "HEAD"),
        )
        if (headCommand.startError != null || headCommand.timedOut || headCommand.exitCode != 0) {
            return null
        }
        val branch = if (
            branchCommand.startError == null &&
            !branchCommand.timedOut &&
            branchCommand.exitCode == 0
        ) {
            safeText(branchCommand.output, MAX_IDENTIFIER_CHARS).trim()
        } else {
            "DETACHED"
        }
        val sha = safeText(headCommand.output, MAX_IDENTIFIER_CHARS).trim()
            .takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
        return HeadSnapshot(branch = branch, sha = sha)
    }

    private fun writeStatus(
        command: CommandResult,
        after: String?,
        head: HeadSnapshot?,
    ): GitOperationStatus {
        return when {
            command.startError != null -> GitOperationStatus.FAILED
            command.timedOut -> GitOperationStatus.UNKNOWN
            command.exitCode != 0 -> GitOperationStatus.FAILED
            after == null || head?.sha == null -> GitOperationStatus.UNKNOWN
            else -> GitOperationStatus.SUCCEEDED
        }
    }

    private fun writeErrorCode(
        command: CommandResult,
        status: GitOperationStatus,
        failureCode: String,
    ): String? {
        return when {
            command.startError != null -> "GIT_UNAVAILABLE"
            command.timedOut -> "GIT_COMMAND_UNKNOWN"
            status == GitOperationStatus.UNKNOWN -> "GIT_COMMAND_UNKNOWN"
            status == GitOperationStatus.SUCCEEDED -> null
            else -> failureCode
        }
    }

    private fun unavailable(
        operation: GitOperation,
        summary: String,
        errorCode: String,
    ): GitOperationResult = GitOperationResult(
        operation = operation,
        status = GitOperationStatus.FAILED,
        summary = summary,
        errorCode = errorCode,
    )

    private fun runGit(root: File, arguments: List<String>): CommandResult {
        val executor = Executors.newSingleThreadExecutor()
        val process = try {
            ProcessBuilder(
                listOf(
                    "git",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "core.fsmonitor=false",
                ) + arguments,
            )
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_OPTIONAL_LOCKS"] = "0"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["GCM_INTERACTIVE"] = "Never"
                }
                .start()
        } catch (error: IOException) {
            executor.shutdownNow()
            return CommandResult(
                exitCode = -1,
                output = "",
                startError = error.message ?: "git unavailable",
            )
        }

        val capture = executor.submit<CapturedOutput> {
            captureOutput(process, MAX_COMMAND_OUTPUT_CHARS)
        }
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = runCatching {
            capture.get(2, TimeUnit.SECONDS)
        }.getOrElse {
            process.destroyForcibly()
            CapturedOutput("", true)
        }
        executor.shutdownNow()
        return CommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = output.text,
            timedOut = !finished,
            truncated = output.truncated,
        )
    }

    private fun captureOutput(
        process: Process,
        maxChars: Int,
    ): CapturedOutput {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        process.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val remaining = maxChars - output.size()
                if (remaining > 0) {
                    output.write(buffer, 0, minOf(count, remaining))
                }
                if (count > remaining) truncated = true
            }
        }
        return CapturedOutput(
            text = output.toByteArray().toString(Charsets.UTF_8),
            truncated = truncated,
        )
    }

    private fun safeText(value: String?, maxChars: Int): String {
        val withoutUrlCredentials = value.orEmpty().replace(URL_CREDENTIAL_PATTERN) { match ->
            match.groupValues[1] + "<redacted>@"
        }
        return AgentRedactor.text(withoutUrlCredentials, maxChars).orEmpty()
    }

    private data class HeadSnapshot(
        val branch: String?,
        val sha: String?,
    )

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
        val truncated: Boolean = false,
        val startError: String? = null,
    )

    private data class CapturedOutput(
        val text: String,
        val truncated: Boolean,
    )

    private companion object {
        const val MAX_COMMAND_OUTPUT_CHARS = 64_000
        const val MAX_COMMIT_MESSAGE_CHARS = 2_000
        const val MAX_IDENTIFIER_CHARS = 200
        const val GIT_TIMEOUT_SECONDS = 8L
        val URL_CREDENTIAL_PATTERN = Regex(
            "(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@",
        )
    }
}

/**
 * Narrow GitHub REST connector for a manually approved pull request.
 *
 * The token is accepted only for the duration of this call and is never
 * included in GitOperationResult, events or SessionStore records.
 */
class GitHubPullRequestClient {

    suspend fun create(
        request: GitPullRequestRequest,
        token: String,
    ): GitHubPullRequestResult = withContext(Dispatchers.IO) {
        val validated = runCatching { validateRequest(request) }.getOrElse { error ->
            return@withContext GitHubPullRequestResult.Failed(
                error.message ?: "Некорректные аргументы PR",
                "INVALID_ARGUMENTS",
            )
        }
        if (token.isBlank()) {
            return@withContext GitHubPullRequestResult.Failed(
                "GitHub token не задан",
                "GITHUB_TOKEN_MISSING",
            )
        }

        val endpoint = "https://api.github.com/repos/" + validated.repository + "/pulls"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = GITHUB_CONNECT_TIMEOUT_MS
            readTimeout = GITHUB_READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            val body = JSONObject()
                .put("title", validated.title)
                .put("head", validated.head)
                .put("base", validated.base)
                .put("body", validated.body)
                .put("draft", validated.draft)
                .toString()
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val responseText = if (status in 200..299) {
                readBounded(connection.inputStream, MAX_RESPONSE_CHARS)
            } else {
                readBounded(connection.errorStream, MAX_RESPONSE_CHARS)
            }

            if (status != HTTP_CREATED) {
                val message = runCatching {
                    JSONObject(responseText).optString("message")
                }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "GitHub PR HTTP " + status
                return@withContext GitHubPullRequestResult.Failed(
                    safeText(message),
                    if (status in 408..599) {
                        "GITHUB_PR_UNKNOWN"
                    } else {
                        "GITHUB_PR_FAILED"
                    },
                )
            }

            val response = JSONObject(responseText)
            val number = response.optInt("number", 0)
            val url = response.optString("html_url").trim()
            require(number > 0 && url.isNotBlank()) {
                "GitHub PR response не содержит number/html_url"
            }
            val headSha = response.optJSONObject("head")
                ?.optString("sha")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (!SHA_PATTERN.matches(headSha.orEmpty())) {
                return@withContext GitHubPullRequestResult.Failed(
                    "GitHub PR response не содержит подтверждённый head SHA",
                    "GITHUB_PR_UNKNOWN",
                )
            }
            val responseHead = response.optJSONObject("head")
            val responseHeadRef = responseHead?.optString("ref")?.trim().orEmpty()
            val responseHeadLabel = responseHead?.optString("label")?.trim().orEmpty()
            val responseBaseRef = response.optJSONObject("base")
                ?.optString("ref")
                ?.trim()
                .orEmpty()
            if (
                responseHeadRef != validated.head &&
                    responseHeadLabel != validated.head
            ) {
                return@withContext GitHubPullRequestResult.Failed(
                    "GitHub PR response не подтвердил head ref; повтор запрещён до re-check",
                    "GITHUB_PR_UNKNOWN",
                )
            }
            if (responseBaseRef != validated.base) {
                return@withContext GitHubPullRequestResult.Failed(
                    "GitHub PR response не подтвердил base ref; повтор запрещён до re-check",
                    "GITHUB_PR_UNKNOWN",
                )
            }
            if (!headSha.equals(validated.expectedHeadSha, ignoreCase = true)) {
                return@withContext GitHubPullRequestResult.Failed(
                    "GitHub PR создан, но head SHA не совпал с ожидаемым; повтор запрещён до re-check",
                    "GITHUB_PR_UNKNOWN",
                )
            }
            GitHubPullRequestResult.Created(
                number = number,
                url = url,
                title = validated.title,
                head = validated.head,
                base = validated.base,
                headSha = headSha,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            GitHubPullRequestResult.Failed(
                "GitHub PR timeout; результат неизвестен, повторять нельзя до re-check",
                "GITHUB_PR_UNKNOWN",
            )
        } catch (error: Exception) {
            GitHubPullRequestResult.Failed(
                safeText(error.message ?: "Не удалось создать GitHub PR"),
                "GITHUB_PR_UNKNOWN",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream?, maxChars: Int): String {
        if (input == null) return ""
        return input.bufferedReader().use { reader ->
            val buffer = CharArray(4 * 1024)
            val result = StringBuilder(maxChars.coerceAtMost(buffer.size))
            var remaining = maxChars.coerceAtLeast(0)
            while (remaining > 0) {
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                result.append(buffer, 0, count)
                remaining -= count
            }
            result.toString()
        }
    }

    private fun validateRequest(request: GitPullRequestRequest): GitPullRequestRequest {
        val repository = request.repository.trim()
        val parts = repository.split('/')
        require(parts.size == 2 && parts.all { it.matches(REPOSITORY_PART_PATTERN) }) {
            "Repository должен иметь формат owner/name"
        }
        val head = validateRemoteRef(request.head, "head")
        val base = validateRemoteRef(request.base, "base")
        val title = request.title.trim()
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS) {
            "PR title пустой или превышает лимит"
        }
        val body = request.body.trim()
        require(body.length <= MAX_BODY_CHARS) {
            "PR body превышает лимит"
        }
        val expectedHeadSha = request.expectedHeadSha?.trim().orEmpty()
        require(SHA_PATTERN.matches(expectedHeadSha)) {
            "Для PR нужен подтверждённый expected head SHA"
        }
        val sessionId = request.sessionId?.trim().orEmpty()
        require(SESSION_ID_PATTERN.matches(sessionId)) {
            "Для PR нужен session ID"
        }
        return request.copy(
            repository = repository,
            head = head,
            base = base,
            title = title,
            body = body,
            expectedHeadSha = expectedHeadSha,
            sessionId = sessionId,
        )
    }

    private fun validateRemoteRef(value: String, label: String): String {
        val ref = value.trim()
        require(ref.length in 1..200) { label + " имеет недопустимую длину" }
        require(
            !ref.startsWith("-") &&
                !ref.startsWith("/") &&
                !ref.endsWith("/") &&
                !ref.endsWith(".") &&
                !ref.contains("..") &&
                !ref.contains("@{") &&
                !ref.contains("//") &&
                ref.none { it.isWhitespace() || it == '\u0000' },
        ) {
            label + " имеет недопустимый формат"
        }
        require(
            ref.all { char ->
                char.isLetterOrDigit() || char in "._/@:-"
            },
        ) {
            label + " содержит недопустимый символ"
        }
        return ref
    }

    private fun safeText(value: String): String {
        return AgentRedactor.text(value, MAX_RESPONSE_CHARS).orEmpty()
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val GITHUB_CONNECT_TIMEOUT_MS = 20_000
        const val GITHUB_READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_CHARS = 8_000
        const val MAX_TITLE_CHARS = 240
        const val MAX_BODY_CHARS = 16_000
        val REPOSITORY_PART_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
        val SHA_PATTERN = Regex("[A-Fa-f0-9]{40,64}")
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}
