package dev.deepagent.mobile.agent.tools

import dev.deepagent.mobile.agent.patch.PatchEngine
import dev.deepagent.mobile.agent.patch.PatchPreview
import dev.deepagent.mobile.agent.model.PatchRollbackResult
import dev.deepagent.mobile.agent.model.PatchRollbackStatus
import dev.deepagent.mobile.agent.git.GitBranchRequest
import dev.deepagent.mobile.agent.git.GitCommitRequest
import dev.deepagent.mobile.agent.git.GitOperationResult
import dev.deepagent.mobile.agent.git.GitOperationStatus
import dev.deepagent.mobile.agent.git.GitPushRequest
import dev.deepagent.mobile.agent.git.GitRepositoryClient
import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.workspace.WorkspacePathPolicy
import dev.deepagent.mobile.agent.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
) {
    init {
        // Keep provider-side schemas strict even when a new tool definition
        // forgets to repeat the root-level guard.
        parameters.put("additionalProperties", false)
    }
}

data class ToolExecutionResult(
    val toolName: String,
    val ok: Boolean,
    val summary: String,
    val content: String = "",
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val patchPreview: PatchPreview? = null,
    val patchCheckpointId: String? = null,
    val workspaceFingerprintAfter: String? = null,
) {
    fun toModelJson(): String = JSONObject()
        .put("tool", AgentRedactor.text(toolName, MAX_TOOL_NAME_CHARS))
        .put("ok", ok)
        .put("summary", AgentRedactor.text(summary, MAX_SUMMARY_CHARS))
        .put("content", AgentRedactor.text(content, MAX_CONTENT_CHARS))
        .put("truncated", truncated || content.length > MAX_CONTENT_CHARS)
        .put("error_code", AgentRedactor.text(errorCode, MAX_ERROR_CODE_CHARS))
        .put("preview", patchPreview?.toJson())
        .put(
            "patch_checkpoint_id",
            AgentRedactor.text(patchCheckpointId, MAX_IDENTIFIER_CHARS),
        )
        .put(
            "workspace_fingerprint_after",
            AgentRedactor.text(workspaceFingerprintAfter, MAX_IDENTIFIER_CHARS),
        )
        .toString()

    private companion object {
        const val MAX_TOOL_NAME_CHARS = 160
        const val MAX_SUMMARY_CHARS = 2_000
        const val MAX_CONTENT_CHARS = 128 * 1024
        const val MAX_ERROR_CODE_CHARS = 96
        const val MAX_IDENTIFIER_CHARS = 80
    }
}

/**
 * Read-only tools remain the default boundary; apply_patch is a preview-only
 * request routed through Agent Core approval and never writes from execute().
 *
 * There is deliberately no generic shell entry point here. Git operations use
 * fixed argument lists and are executed only against the selected private
 * workspace. Every filesystem path is canonicalized and checked against that
 * workspace root before it is used.
 */
class ToolRouter(
    private val workspaceManager: WorkspaceManager,
) {

    private val patchLock = Any()
    private val gitRepository = GitRepositoryClient(workspaceManager)

    suspend fun previewPatch(
        argumentsJson: String,
        workspaceId: String? = null,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return@withContext ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "Некорректные аргументы apply_patch",
                    errorCode = "INVALID_ARGUMENTS",
                )
            }
        val root = workspaceManager.resolveRoot(workspaceId)
            ?: return@withContext ToolExecutionResult(
                toolName = TOOL_APPLY_PATCH,
                ok = false,
                summary = "Workspace не выбран или недоступен",
                errorCode = "WORKSPACE_UNAVAILABLE",
            )
        val checkpointDirectory = workspaceManager.checkpointDirectory(workspaceId)
            ?: return@withContext ToolExecutionResult(
                toolName = TOOL_APPLY_PATCH,
                ok = false,
                summary = "Checkpoint directory недоступна",
                errorCode = "CHECKPOINT_UNAVAILABLE",
            )

        return@withContext try {
            validateArguments(TOOL_APPLY_PATCH, arguments)
            val identity = workspaceManager.captureIdentity(workspaceId)
                ?: error("Workspace identity недоступна")
            val preview = PatchEngine(checkpointDirectory).preview(
                workspaceRoot = root,
                arguments = arguments,
                workspaceFingerprint = identity.treeSha256,
            )
            ToolExecutionResult(
                toolName = TOOL_APPLY_PATCH,
                ok = true,
                summary = "Patch preview создан; запись не выполнена",
                content = preview.unifiedDiff,
                patchPreview = preview,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult(
                toolName = TOOL_APPLY_PATCH,
                ok = false,
                summary = error.message ?: "Не удалось построить patch preview",
                errorCode = "PATCH_PREVIEW_FAILED",
            )
        }
    }

    suspend fun applyPatch(
        argumentsJson: String,
        workspaceId: String? = null,
        expectedWorkspaceFingerprint: String,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(expectedWorkspaceFingerprint.isNotBlank()) {
            "Для apply_patch нужен workspace fingerprint"
        }
        synchronized(patchLock) {
            val arguments = runCatching {
                JSONObject(argumentsJson.ifBlank { "{}" })
            }.getOrElse {
                return@withContext ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "Некорректные аргументы apply_patch",
                    errorCode = "INVALID_ARGUMENTS",
                )
            }
            val root = workspaceManager.resolveRoot(workspaceId)
                ?: return@withContext ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "Workspace не выбран или недоступен",
                    errorCode = "WORKSPACE_UNAVAILABLE",
                )
            val checkpointDirectory = workspaceManager.checkpointDirectory(workspaceId)
                ?: return@withContext ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "Checkpoint directory недоступна",
                    errorCode = "CHECKPOINT_UNAVAILABLE",
                )
            val identity = runCatching {
                workspaceManager.captureIdentity(workspaceId)
            }.getOrNull()
            if (identity == null) {
                return@withContext ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "Workspace identity недоступна; требуется re-check",
                    errorCode = "WORKSPACE_RECHECK_REQUIRED",
                )
            }
            if (identity.treeSha256 != expectedWorkspaceFingerprint) {
                return@withContext ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "Workspace изменился после preview; требуется новый preview",
                    errorCode = "WORKSPACE_CHANGED_RECHECK_REQUIRED",
                )
            }

            return@withContext try {
                validateArguments(TOOL_APPLY_PATCH, arguments)
                val patchEngine = PatchEngine(checkpointDirectory)
                val applied = patchEngine.apply(
                    workspaceRoot = root,
                    arguments = arguments,
                    expectedWorkspaceFingerprint = expectedWorkspaceFingerprint,
                )
                val afterIdentity = runCatching {
                    workspaceManager.captureIdentity(workspaceId)
                }.getOrNull()
                val afterFingerprint = afterIdentity?.treeSha256
                if (afterFingerprint == null) {
                    runCatching {
                        patchEngine.markCheckpointUnknown(
                            applied.operationId,
                            "PATCH_APPLIED_RECHECK_REQUIRED",
                        )
                    }
                    return@withContext ToolExecutionResult(
                        toolName = TOOL_APPLY_PATCH,
                        ok = false,
                        summary = "Patch применён, но fingerprint после записи не проверен",
                        content = applied.toJson()
                            .put("workspace_fingerprint_after", JSONObject.NULL)
                            .put("checkpoint_status", "UNKNOWN")
                            .toString(),
                        errorCode = "PATCH_APPLIED_RECHECK_REQUIRED",
                        patchPreview = applied.preview,
                        patchCheckpointId = applied.operationId,
                    )
                }

                val checkpoint = runCatching {
                    patchEngine.markCheckpointApplied(
                        applied.operationId,
                        afterFingerprint,
                    )
                }.getOrNull()
                if (checkpoint == null) {
                    runCatching {
                        patchEngine.markCheckpointUnknown(
                            applied.operationId,
                            "PATCH_CHECKPOINT_UNCONFIRMED",
                        )
                    }
                    return@withContext ToolExecutionResult(
                        toolName = TOOL_APPLY_PATCH,
                        ok = false,
                        summary = "Patch применён, но checkpoint не подтверждён",
                        content = applied.toJson()
                            .put("workspace_fingerprint_after", afterFingerprint)
                            .put("checkpoint_status", "UNKNOWN")
                            .toString(),
                        errorCode = "PATCH_CHECKPOINT_UNCONFIRMED",
                        patchPreview = applied.preview,
                        patchCheckpointId = applied.operationId,
                        workspaceFingerprintAfter = afterFingerprint,
                    )
                }

                ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = true,
                    summary = "Patch применён после явного approval",
                    content = applied.toJson()
                        .put("workspace_fingerprint_after", afterFingerprint)
                        .put("checkpoint_status", checkpoint.status.name)
                        .toString(),
                    patchPreview = applied.preview,
                    patchCheckpointId = applied.operationId,
                    workspaceFingerprintAfter = afterFingerprint,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = error.message ?: "Не удалось применить patch",
                    errorCode = "PATCH_APPLY_FAILED",
                )
            }
        }
    }


    suspend fun rollbackPatch(
        workspaceId: String? = null,
        operationId: String,
        expectedWorkspaceFingerprint: String,
    ): PatchRollbackResult = withContext(Dispatchers.IO) {
        if (operationId.isBlank() || expectedWorkspaceFingerprint.isBlank()) {
            return@withContext PatchRollbackResult(
                operationId = operationId.takeIf { it.isNotBlank() },
                status = PatchRollbackStatus.FAILED,
                summary = "Для rollback нужны operation id и workspace fingerprint",
                errorCode = "PATCH_ROLLBACK_INVALID_ARGUMENTS",
            )
        }
        synchronized(patchLock) {
            val root = workspaceManager.resolveRoot(workspaceId)
            val checkpointDirectory = workspaceManager.checkpointDirectory(workspaceId)
            when {
                root == null -> PatchRollbackResult(
                    operationId = operationId,
                    status = PatchRollbackStatus.FAILED,
                    summary = "Workspace не выбран или недоступен",
                    errorCode = "WORKSPACE_UNAVAILABLE",
                )

                checkpointDirectory == null -> PatchRollbackResult(
                    operationId = operationId,
                    status = PatchRollbackStatus.FAILED,
                    summary = "Checkpoint directory недоступна",
                    errorCode = "CHECKPOINT_UNAVAILABLE",
                )

                else -> PatchEngine(checkpointDirectory).rollback(
                    workspaceRoot = root,
                    operationId = operationId,
                    expectedWorkspaceFingerprint = expectedWorkspaceFingerprint,
                )
            }
        }
    }


    suspend fun readProjectRules(
        workspaceId: String? = null,
    ): ToolExecutionResult {
        return execute(
            toolName = TOOL_READ_FILE,
            argumentsJson = JSONObject()
                .put("path", "AGENT_RULES.md")
                .put("max_bytes", MAX_PROJECT_RULE_BYTES)
                .toString(),
            workspaceId = workspaceId,
        )
    }

    suspend fun inspectGit(workspaceId: String? = null): GitOperationResult =
        gitRepository.status(workspaceId)

    suspend fun currentGitHeadSha(workspaceId: String? = null): String? {
        val result = inspectGit(workspaceId)
        return result.headSha.takeIf {
            result.status == GitOperationStatus.SUCCEEDED
        }
    }

    suspend fun createGitBranch(
        workspaceId: String?,
        request: GitBranchRequest,
    ): GitOperationResult = gitRepository.createBranch(workspaceId, request)

    suspend fun commitGit(
        workspaceId: String?,
        request: GitCommitRequest,
    ): GitOperationResult = gitRepository.commit(workspaceId, request)

    suspend fun pushGit(
        workspaceId: String?,
        request: GitPushRequest,
    ): GitOperationResult = gitRepository.push(workspaceId, request)

    suspend fun execute(
        toolName: String,
        argumentsJson: String,
        workspaceId: String? = null,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return@withContext ToolExecutionResult(
                    toolName = toolName,
                    ok = false,
                    summary = "Некорректные аргументы инструмента",
                    errorCode = "INVALID_ARGUMENTS",
                )
            }

        val argumentError = runCatching {
            validateArguments(toolName, arguments)
        }.exceptionOrNull()
        if (argumentError != null) {
            return@withContext ToolExecutionResult(
                toolName = toolName,
                ok = false,
                summary = "Некорректные аргументы инструмента",
                errorCode = "INVALID_ARGUMENTS",
            )
        }

        val root = workspaceManager.resolveRoot(workspaceId)
            ?: return@withContext ToolExecutionResult(
                toolName = toolName,
                ok = false,
                summary = "Workspace не выбран или недоступен",
                errorCode = "WORKSPACE_UNAVAILABLE",
            )

        return@withContext try {
            when (toolName) {
                TOOL_LIST_FILES -> listFiles(root, arguments)
                TOOL_READ_FILE -> readFile(root, arguments)
                TOOL_SEARCH_CODE -> searchCode(root, arguments)
                TOOL_GIT_STATUS -> gitStatus(root)
                TOOL_GIT_DIFF -> gitDiff(root, arguments)
                TOOL_APPLY_PATCH -> ToolExecutionResult(
                    toolName = TOOL_APPLY_PATCH,
                    ok = false,
                    summary = "apply_patch требует preview и явного approval UI",
                    errorCode = "WRITE_REQUIRES_APPROVAL",
                )
                else -> ToolExecutionResult(
                    toolName = toolName,
                    ok = false,
                    summary = "Инструмент не разрешён в P0",
                    errorCode = "TOOL_NOT_ALLOWED",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult(
                toolName = toolName,
                ok = false,
                summary = error.message ?: "Ошибка read-only инструмента",
                errorCode = "TOOL_FAILED",
            )
        }
    }

    private fun validateArguments(toolName: String, arguments: JSONObject) {
        val schema = when (toolName) {
            TOOL_LIST_FILES -> ArgumentSchema(
                allowed = setOf("path", "max_depth", "max_entries", "include_hidden"),
            )
            TOOL_READ_FILE -> ArgumentSchema(
                allowed = setOf("path", "max_bytes"),
                required = setOf("path"),
            )
            TOOL_SEARCH_CODE -> ArgumentSchema(
                allowed = setOf("query", "path", "max_results", "max_file_bytes"),
                required = setOf("query"),
            )
            TOOL_GIT_STATUS -> ArgumentSchema(allowed = emptySet())
            TOOL_GIT_DIFF -> ArgumentSchema(allowed = setOf("path"))
            TOOL_APPLY_PATCH -> ArgumentSchema(
                allowed = setOf(
                    "path",
                    "expected_sha256",
                    "patch",
                    "replacement",
                    "create",
                ),
                required = setOf("path", "expected_sha256"),
            )
            else -> error("Инструмент не разрешён")
        }

        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key in schema.allowed) {
                "Неизвестный аргумент инструмента"
            }
        }
        schema.required.forEach { key ->
            require(arguments.has(key) && arguments.opt(key) != JSONObject.NULL) {
                "Отсутствует обязательный аргумент инструмента"
            }
        }

        when (toolName) {
            TOOL_LIST_FILES -> {
                requireOptionalString(arguments, "path", 512)
                requireOptionalInt(arguments, "max_depth", 0L, MAX_DEPTH.toLong())
                requireOptionalInt(arguments, "max_entries", 1L, MAX_ENTRIES_LIMIT.toLong())
                requireOptionalBoolean(arguments, "include_hidden")
            }
            TOOL_READ_FILE -> {
                requireString(arguments, "path", 512)
                requireOptionalInt(arguments, "max_bytes", 1L, MAX_READ_BYTES.toLong())
            }
            TOOL_SEARCH_CODE -> {
                requireString(arguments, "query", MAX_QUERY_LENGTH)
                requireOptionalString(arguments, "path", 512)
                requireOptionalInt(arguments, "max_results", 1L, MAX_RESULTS_LIMIT.toLong())
                requireOptionalInt(
                    arguments,
                    "max_file_bytes",
                    1L,
                    MAX_SEARCH_FILE_BYTES.toLong(),
                )
            }
            TOOL_GIT_DIFF -> requireOptionalString(arguments, "path", 512)
            TOOL_APPLY_PATCH -> {
                requireString(arguments, "path", 512)
                requireString(arguments, "expected_sha256", 128)
                requireOptionalString(arguments, "patch", MAX_PATCH_CHARS)
                requireOptionalString(arguments, "replacement", MAX_PATCH_CHARS)
                requireOptionalBoolean(arguments, "create")
            }
        }
    }

    private fun requireString(
        arguments: JSONObject,
        key: String,
        maxLength: Int,
    ) {
        val value = arguments.opt(key)
        require(value is String && value.length <= maxLength) {
            "Аргумент инструмента должен быть строкой"
        }
    }

    private fun requireOptionalString(
        arguments: JSONObject,
        key: String,
        maxLength: Int,
    ) {
        if (!arguments.has(key) || arguments.opt(key) == JSONObject.NULL) return
        requireString(arguments, key, maxLength)
    }

    private fun requireOptionalInt(
        arguments: JSONObject,
        key: String,
        minimum: Long,
        maximum: Long,
    ) {
        if (!arguments.has(key) || arguments.opt(key) == JSONObject.NULL) return
        val value = arguments.opt(key)
        require(value is Number) {
            "Аргумент инструмента должен быть целым числом"
        }
        val longValue = value.toDouble()
        require(longValue.isFinite() && longValue == value.toLong().toDouble()) {
            "Аргумент инструмента должен быть целым числом"
        }
        require(value.toLong() in minimum..maximum) {
            "Аргумент инструмента выходит за допустимый диапазон"
        }
    }

    private fun requireOptionalBoolean(
        arguments: JSONObject,
        key: String,
    ) {
        if (!arguments.has(key) || arguments.opt(key) == JSONObject.NULL) return
        require(arguments.opt(key) is Boolean) {
            "Аргумент инструмента должен быть boolean"
        }
    }

    private data class ArgumentSchema(
        val allowed: Set<String>,
        val required: Set<String> = emptySet(),
    )

    private fun listFiles(root: File, arguments: JSONObject): ToolExecutionResult {
        val requestedPath = arguments.optString("path")
        val base = resolvePath(root, requestedPath, requireExisting = true)
        require(base.isDirectory) { "Путь не является директорией" }

        val maxDepth = arguments.optInt("max_depth", DEFAULT_MAX_DEPTH)
            .coerceIn(0, MAX_DEPTH)
        val maxEntries = arguments.optInt("max_entries", DEFAULT_MAX_ENTRIES)
            .coerceIn(1, MAX_ENTRIES_LIMIT)
        val includeHidden = arguments.optBoolean("include_hidden", true)
        val output = JSONArray()
        var count = 0
        var wasTruncated = false

        visitTree(
            root = base,
            maxDepth = maxDepth,
            includeHidden = includeHidden,
        ) { file, depth ->
            if (count >= maxEntries) {
                wasTruncated = true
                return@visitTree false
            }
            if (file == base) return@visitTree true

            output.put(
                JSONObject()
                    .put("path", relativePath(root, file))
                    .put("type", if (file.isDirectory) "directory" else "file")
                    .put("size", if (file.isFile) file.length() else JSONObject.NULL)
                    .put("depth", depth),
            )
            count += 1
            true
        }

        return ToolExecutionResult(
            toolName = TOOL_LIST_FILES,
            ok = true,
            summary = "Найдено элементов: " + count,
            content = output.toString(),
            truncated = wasTruncated,
        )
    }

    private fun readFile(root: File, arguments: JSONObject): ToolExecutionResult {
        val requestedPath = arguments.optString("path").trim()
        require(requestedPath.isNotBlank()) { "Для read_file нужен path" }
        val file = resolvePath(root, requestedPath, requireExisting = true)
        require(file.isFile) { "Путь не является файлом" }
        if (isSensitiveFile(file)) {
            return ToolExecutionResult(
                toolName = TOOL_READ_FILE,
                ok = false,
                summary = "Файл скрыт политикой redaction",
                errorCode = "SENSITIVE_FILE_BLOCKED",
            )
        }

        val maxBytes = arguments.optInt("max_bytes", DEFAULT_READ_BYTES)
            .coerceIn(1, MAX_READ_BYTES)
        val bounded = readBounded(file, maxBytes)
        val bytes = bounded.bytes
        val wasTruncated = bounded.truncated
        val visible = if (wasTruncated) bytes.copyOf(maxBytes) else bytes
        require(!visible.contains(0.toByte())) {
            "Бинарный файл не принимается read_file"
        }

        return ToolExecutionResult(
            toolName = TOOL_READ_FILE,
            ok = true,
            summary = "Файл прочитан: " + relativePath(root, file),
            content = visible.toString(Charsets.UTF_8),
            truncated = wasTruncated,
        )
    }

    private fun searchCode(root: File, arguments: JSONObject): ToolExecutionResult {
        val query = arguments.optString("query").trim()
        require(query.isNotBlank()) { "Для search_code нужен query" }
        require(query.length <= MAX_QUERY_LENGTH) {
            "Поисковый запрос слишком длинный"
        }

        val requestedPath = arguments.optString("path")
        val base = resolvePath(root, requestedPath, requireExisting = true)
        val maxResults = arguments.optInt("max_results", DEFAULT_MAX_RESULTS)
            .coerceIn(1, MAX_RESULTS_LIMIT)
        val maxFileBytes = arguments.optInt("max_file_bytes", DEFAULT_SEARCH_FILE_BYTES)
            .coerceIn(1, MAX_SEARCH_FILE_BYTES)
        val output = JSONArray()
        var matches = 0
        var wasTruncated = false

        visitTree(
            root = base,
            maxDepth = MAX_SEARCH_DEPTH,
            includeHidden = true,
        ) { file, _ ->
            if (matches >= maxResults) {
                wasTruncated = true
                return@visitTree false
            }
            if (!file.isFile ||
                isSensitiveFile(file) ||
                file.length() > maxFileBytes
            ) return@visitTree true

            val bounded = readBounded(file, maxFileBytes)
            if (bounded.truncated) return@visitTree true
            val bytes = bounded.bytes
            if (bytes.contains(0.toByte())) return@visitTree true
            val text = bytes.toString(Charsets.UTF_8)
            text.lineSequence().forEachIndexed { index, line ->
                if (matches >= maxResults) {
                    wasTruncated = true
                    return@forEachIndexed
                }
                if (line.contains(query, ignoreCase = true)) {
                    output.put(
                        JSONObject()
                            .put("path", relativePath(root, file))
                            .put("line", index + 1)
                            .put("text", line.trim().take(MAX_MATCH_LINE_LENGTH)),
                    )
                    matches += 1
                }
            }
            true
        }

        return ToolExecutionResult(
            toolName = TOOL_SEARCH_CODE,
            ok = true,
            summary = "Совпадений: " + matches,
            content = output.toString(),
            truncated = wasTruncated,
        )
    }

    private fun gitStatus(root: File): ToolExecutionResult {
        if (!hasSafeGitMetadata(root)) {
            return ToolExecutionResult(
                toolName = TOOL_GIT_STATUS,
                ok = false,
                summary = "Workspace не содержит .git",
                errorCode = "NOT_A_GIT_REPOSITORY",
            )
        }
        return runGit(
            root = root,
            toolName = TOOL_GIT_STATUS,
            arguments = listOf(
                "status",
                "--short",
                "--branch",
                "--untracked-files=all",
            ),
        )
    }

    private fun gitDiff(root: File, arguments: JSONObject): ToolExecutionResult {
        if (!hasSafeGitMetadata(root)) {
            return ToolExecutionResult(
                toolName = TOOL_GIT_DIFF,
                ok = false,
                summary = "Workspace не содержит .git",
                errorCode = "NOT_A_GIT_REPOSITORY",
            )
        }

        val requestedPath = arguments.optString("path").trim()
        val gitArguments = mutableListOf(
            "diff",
            "--no-ext-diff",
            "--no-textconv",
            "--no-renames",
            "--",
        )
        if (requestedPath.isNotBlank()) {
            val file = resolvePath(root, requestedPath, requireExisting = false)
            require(!file.exists() || file.isFile) {
                "git_diff path должен указывать на файл"
            }
            gitArguments += relativePath(root, file)
        } else {
            gitArguments += "."
            gitArguments += SAFE_DIFF_EXCLUDES
        }

        return runGit(root, TOOL_GIT_DIFF, gitArguments)
    }

    private fun hasSafeGitMetadata(root: File): Boolean {
        val metadata = File(root, ".git")
        return metadata.isDirectory && !Files.isSymbolicLink(metadata.toPath())
    }

    private fun runGit(
        root: File,
        toolName: String,
        arguments: List<String>,
    ): ToolExecutionResult {
        val command = listOf(
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "-c",
            "core.fsmonitor=false",
        ) + arguments
        val executor = Executors.newSingleThreadExecutor()
        val process = try {
            ProcessBuilder(command)
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
            return ToolExecutionResult(
                toolName = toolName,
                ok = false,
                summary = "Команда git недоступна на устройстве",
                errorCode = "GIT_UNAVAILABLE",
            )
        }

        val capture = executor.submit<CapturedOutput> {
            captureOutput(process, MAX_GIT_OUTPUT_CHARS)
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

        val exitCode = if (finished) process.exitValue() else -1
        return ToolExecutionResult(
            toolName = toolName,
            ok = finished && exitCode == 0,
            summary = if (finished && exitCode == 0) {
                "git " + arguments.firstOrNull().orEmpty() + " выполнен"
            } else {
                "git завершился с кодом " + exitCode
            },
            content = output.text,
            truncated = output.truncated,
            errorCode = if (finished && exitCode == 0) null else "GIT_COMMAND_FAILED",
        )
    }

    private fun captureOutput(process: Process, maxChars: Int): CapturedOutput {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        process.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val remaining = maxChars - output.size()
                if (remaining > 0) {
                    val copied = minOf(count, remaining)
                    output.write(buffer, 0, copied)
                    if (copied < count) truncated = true
                } else {
                    truncated = true
                }
            }
        }
        return CapturedOutput(
            text = output.toByteArray().toString(Charsets.UTF_8),
            truncated = truncated,
        )
    }

    private fun resolvePath(
        root: File,
        requestedPath: String,
        requireExisting: Boolean,
    ): File = WorkspacePathPolicy.resolve(
        root = root,
        requestedPath = requestedPath,
        requireExisting = requireExisting,
    )

    private fun relativePath(root: File, file: File): String {
        val rootUri = root.canonicalFile.toURI()
        return rootUri.relativize(file.canonicalFile.toURI()).path
            .trimEnd('/')
            .ifBlank { "." }
    }

    private fun visitTree(
        root: File,
        maxDepth: Int,
        includeHidden: Boolean,
        visitor: (File, Int) -> Boolean,
    ) {
        fun visit(directory: File, depth: Int): Boolean {
            if (!visitor(directory, depth)) return false
            if (!directory.isDirectory || depth >= maxDepth) return true

            val children = directory.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                ?: return true
            for (child in children) {
                require(!Files.isSymbolicLink(child.toPath())) {
                    "Symbolic link запрещён в workspace: " + child.name
                }
                if (child.isDirectory && isIgnoredDirectory(child)) continue
                if (isSensitiveFile(child)) continue
                if (!includeHidden && child.name.startsWith(".")) continue
                if (!visit(child, depth + 1)) return false
            }
            return true
        }
        visit(root, 0)
    }

    private fun isIgnoredDirectory(file: File): Boolean {
        return file.name in IGNORED_DIRECTORIES
    }

    private fun isSensitiveFile(file: File): Boolean =
        WorkspacePathPolicy.isSensitiveFile(file)

    private fun readBounded(file: File, maxBytes: Int): BoundedRead {
        val output = ByteArrayOutputStream(
            minOf(maxBytes + 1, READ_BUFFER_SIZE),
        )
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var truncated = false
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val remaining = maxBytes + 1 - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val copied = minOf(count, remaining)
                output.write(buffer, 0, copied)
                if (copied < count) {
                    truncated = true
                    break
                }
            }
        }
        return BoundedRead(
            bytes = output.toByteArray(),
            truncated = truncated || output.size() > maxBytes,
        )
    }

    private data class BoundedRead(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    private data class CapturedOutput(
        val text: String,
        val truncated: Boolean,
    )

    companion object {
        const val TOOL_LIST_FILES = "list_files"
        const val TOOL_READ_FILE = "read_file"
        const val TOOL_SEARCH_CODE = "search_code"
        const val TOOL_GIT_STATUS = "git_status"
        const val TOOL_GIT_DIFF = "git_diff"
        const val TOOL_APPLY_PATCH = "apply_patch"

        const val DEFAULT_MAX_DEPTH = 4
        const val MAX_DEPTH = 8
        const val DEFAULT_MAX_ENTRIES = 300
        const val MAX_ENTRIES_LIMIT = 2_000
        const val DEFAULT_READ_BYTES = 512 * 1024
        const val MAX_READ_BYTES = 2 * 1024 * 1024
        const val MAX_PROJECT_RULE_BYTES = 16 * 1024
        const val MAX_PATCH_CHARS = 2 * 1024 * 1024
        const val DEFAULT_MAX_RESULTS = 50
        const val MAX_RESULTS_LIMIT = 200
        const val DEFAULT_SEARCH_FILE_BYTES = 512 * 1024
        const val MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_SEARCH_DEPTH = 8
        const val MAX_QUERY_LENGTH = 256
        const val MAX_MATCH_LINE_LENGTH = 500
        const val READ_BUFFER_SIZE = 16 * 1024
        const val MAX_GIT_OUTPUT_CHARS = 100_000
        const val GIT_TIMEOUT_SECONDS = 8L
        val SAFE_DIFF_EXCLUDES = listOf(
            ":(exclude,icase)**/.env",
            ":(exclude,icase)**/.env.*",
            ":(exclude,icase)**/.npmrc",
            ":(exclude,icase)**/.netrc",
            ":(exclude,icase)**/*.p12",
            ":(exclude,icase)**/*.pfx",
            ":(exclude,icase)**/*.jks",
            ":(exclude,icase)**/*.keystore",
            ":(exclude,icase)**/google-services.json",
            ":(exclude,icase)**/gradle.properties",
            ":(exclude,icase)**/local.properties",
            ":(exclude,icase)**/id_rsa",
            ":(exclude,icase)**/id_ed25519",
            ":(exclude,icase)**/*.pem",
            ":(exclude,icase)**/*.key",
            ":(exclude,icase)**/*.der",
            ":(exclude,icase)**/*.asc",
            ":(exclude,icase)**/*.gpg",
            ":(exclude,icase)**/*credential*",
            ":(exclude,icase)**/*secret*",
            ":(exclude,icase)**/*password*",
        )
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", "build", "node_modules")

        fun definitions(): List<AgentToolDefinition> = listOf(
            AgentToolDefinition(
                name = TOOL_LIST_FILES,
                description = "List source files and directories in the selected workspace.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("path", JSONObject().put("type", "string"))
                            .put("max_depth", JSONObject().put("type", "integer"))
                            .put("max_entries", JSONObject().put("type", "integer"))
                            .put("include_hidden", JSONObject().put("type", "boolean")),
                    ),
            ),
            AgentToolDefinition(
                name = TOOL_READ_FILE,
                description = "Read one UTF-8 text file from the selected workspace.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("path", JSONObject().put("type", "string"))
                            .put("max_bytes", JSONObject().put("type", "integer")),
                        )
                    .put("required", JSONArray().put("path")),
            ),
            AgentToolDefinition(
                name = TOOL_SEARCH_CODE,
                description = "Search a text query in source files of the selected workspace.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("query", JSONObject().put("type", "string"))
                            .put("path", JSONObject().put("type", "string"))
                            .put("max_results", JSONObject().put("type", "integer"))
                            .put("max_file_bytes", JSONObject().put("type", "integer")),
                        )
                    .put("required", JSONArray().put("query")),
            ),
            AgentToolDefinition(
                name = TOOL_GIT_STATUS,
                description = "Read git branch and working tree status without changing files.",
                parameters = JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject(),
                ),
            ),
            AgentToolDefinition(
                name = TOOL_GIT_DIFF,
                description = "Read a bounded git diff from the selected workspace.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put("path", JSONObject().put("type", "string")),
                    ),
            ),
            AgentToolDefinition(
                name = TOOL_APPLY_PATCH,
                description = "Prepare a text patch preview. The host never writes immediately; a user approval is required.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("path", JSONObject().put("type", "string"))
                            .put("expected_sha256", JSONObject().put("type", "string"))
                            .put("patch", JSONObject().put("type", "string"))
                            .put("replacement", JSONObject().put("type", "string"))
                            .put("create", JSONObject().put("type", "boolean")),
                    )
                    .put(
                        "required",
                        JSONArray()
                            .put("path")
                            .put("expected_sha256"),
                    ),
            ),
        )
    }
}
