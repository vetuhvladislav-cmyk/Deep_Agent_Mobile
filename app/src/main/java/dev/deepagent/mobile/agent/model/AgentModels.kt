package dev.deepagent.mobile.agent.model

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class ExecutionTarget {
    AUTO,
    LOCAL_LITE,
    REMOTE_ACTIONS,
}

enum class PermissionMode {
    READ_ONLY,
    LOCAL_WRITE,
    GITHUB_WRITE,
    PR_CREATE,
    MERGE_RELEASE;

    /**
     * Permission presets are capability bundles, not an ordinal hierarchy.
     * Local workspace mutation is intentionally independent from GitHub access.
     */
    fun allows(required: PermissionMode): Boolean = when (required) {
        READ_ONLY -> true
        LOCAL_WRITE -> this == LOCAL_WRITE || this == MERGE_RELEASE
        GITHUB_WRITE -> this == GITHUB_WRITE || this == PR_CREATE || this == MERGE_RELEASE
        PR_CREATE -> this == PR_CREATE || this == MERGE_RELEASE
        MERGE_RELEASE -> this == MERGE_RELEASE
    }
}

enum class AgentEventKind {
    SESSION,
    PLAN,
    REASONING,
    OUTPUT,
    TOOL,
    IMAGE,
    BUILD,
    APPROVAL,
    ARTIFACT,
    ERROR,
    INFO,
}

enum class AgentSessionStatus {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

data class ImageAttachment(
    val dataUrl: String,
    val mediaType: String,
    val detail: String = "auto",
    val displayName: String? = null,
)

data class AgentRequest(
    val task: String,
    val target: ExecutionTarget = ExecutionTarget.AUTO,
    val permission: PermissionMode = PermissionMode.READ_ONLY,
    val image: ImageAttachment? = null,
    val imageAssetId: String? = null,
    val deepSeekApiKey: String? = null,
    val deepSeekBaseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val githubToken: String? = null,
    val repository: String? = null,
    val workflow: String? = null,
    val ref: String = "main",
    val sessionId: String? = null,
    val workspaceId: String? = null,
)

data class AgentEvent(
    val kind: AgentEventKind,
    val message: String,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sessionId: String? = null,
    val eventId: String = UUID.randomUUID().toString(),
    val sequence: Long = 0L,
    val workspaceId: String? = null,
    val invocationId: String? = null,
)

data class AgentSessionState(
    val status: AgentSessionStatus = AgentSessionStatus.IDLE,
    val target: ExecutionTarget? = null,
    val task: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val lastError: String? = null,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val eventCursor: Long = 0L,
    val recoveryRequired: Boolean = false,
)

    
data class CredentialState(
    val deepSeekConfigured: Boolean = false,
    val githubConfigured: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("deep_seek_configured", deepSeekConfigured)
        .put("github_configured", githubConfigured)
        .put("updated_at", updatedAt)
}

enum class JournalExportStatus {
    EXPORTED,
    FAILED,
    UNKNOWN,
}

data class JournalExportResult(
    val sessionId: String? = null,
    val status: JournalExportStatus,
    val bytes: Long = 0L,
    val summary: String,
    val errorCode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("status", status.name)
        .put("bytes", bytes)
        .put("summary", AgentRedactor.text(summary, 2_000))
        .put("error_code", AgentRedactor.text(errorCode, 96))
}

data class AgentWorkspaceSnapshot(
    val id: String,
    val displayName: String,
    val sourceType: String,
    val fileCount: Int,
    val totalBytes: Long,
    val importedAt: Long,
    val fingerprint: String? = null,
    val repository: String? = null,
    val ref: String? = null,
    val commitSha: String? = null,
)

data class PendingPatchApproval(
    val sessionId: String,
    val workspaceId: String,
    val path: String,
    val workspaceFingerprint: String,
    val oldSha256: String?,
    val newSha256: String,
    val unifiedDiff: String,
    val canApply: Boolean,
)







enum class ImageAnalysisStatus {
    IDLE,
    VALIDATING,
    READY,
    ANALYZING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

data class ImageAnalysisState(
    val status: ImageAnalysisStatus = ImageAnalysisStatus.IDLE,
    val assetId: String? = null,
    val displayName: String? = null,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val checksum: String? = null,
    val summary: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("asset_id", AgentRedactor.text(assetId, 160))
        .put("display_name", AgentRedactor.text(displayName, 200))
        .put("media_type", AgentRedactor.text(mediaType, 64))
        .put("size_bytes", sizeBytes)
        .put("width", width)
        .put("height", height)
        .put("checksum", AgentRedactor.text(checksum, 80))
        .put("summary", AgentRedactor.text(summary, 2_000))
        .put("error_code", AgentRedactor.text(errorCode, 96))
        .put("updated_at", updatedAt)
}



enum class WorkspaceCatalogStatus {
    IDLE,
    LOADING,
    READY,
    FAILED,
}

data class WorkspaceFileEntry(
    val path: String,
    val type: String,
    val sizeBytes: Long = 0L,
    val depth: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", AgentRedactor.text(path, 512))
        .put("type", AgentRedactor.text(type, 32))
        .put("size_bytes", sizeBytes)
        .put("depth", depth)
}

data class WorkspaceTreePage(
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val truncated: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(
            "entries",
            JSONArray().apply {
                entries.take(300).forEach { put(it.toJson()) }
            },
        )
        .put("truncated", truncated)
}

data class WorkspaceRulesMetadata(
    val path: String = "AGENT_RULES.md",
    val available: Boolean = false,
    val sizeBytes: Int = 0,
    val truncated: Boolean = false,
)

data class WorkspaceCatalogState(
    val selectedId: String? = null,
    val items: List<AgentWorkspaceSnapshot> = emptyList(),
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val entriesTruncated: Boolean = false,
    val fingerprint: String? = null,
    val rules: WorkspaceRulesMetadata = WorkspaceRulesMetadata(),
    val status: WorkspaceCatalogStatus = WorkspaceCatalogStatus.IDLE,
    val summary: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("selected_id", AgentRedactor.text(selectedId, 160))
        .put(
            "items",
            JSONArray().apply {
                items.take(32).forEach { item ->
                    put(
                        JSONObject()
                            .put("id", AgentRedactor.text(item.id, 160))
                            .put("display_name", AgentRedactor.text(item.displayName, 200))
                            .put("source_type", AgentRedactor.text(item.sourceType, 64))
                            .put("file_count", item.fileCount)
                            .put("total_bytes", item.totalBytes)
                            .put("fingerprint", AgentRedactor.text(item.fingerprint, 80)),
                    )
                }
            },
        )
        .put("tree", WorkspaceTreePage(entries, entriesTruncated).toJson())
        .put("fingerprint", AgentRedactor.text(fingerprint, 80))
        .put("rules_path", AgentRedactor.text(rules.path, 160))
        .put("rules_available", rules.available)
        .put("rules_bytes", rules.sizeBytes)
        .put("rules_truncated", rules.truncated)
        .put("status", status.name)
        .put("summary", AgentRedactor.text(summary, 2_000))
        .put("error_code", AgentRedactor.text(errorCode, 96))
        .put("updated_at", updatedAt)
}

enum class InteractiveSessionStatus {
    IDLE,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

data class InteractiveCommandRequest(
    val executable: String,
    val args: List<String> = emptyList(),
    val workspaceId: String? = null,
    val cwd: String = ".",
    val input: String? = null,
    val timeoutMs: Long = 30_000L,
    val sessionId: String? = null,
    val env: Map<String, String> = emptyMap(),
) {
    fun toAuditJson(): JSONObject = JSONObject()
        .put("executable", AgentRedactor.text(executable, 96))
        .put(
            "args",
            JSONArray().apply {
                args.take(32).forEach { put(AgentRedactor.text(it, 256)) }
            },
        )
        .put("workspace_id", AgentRedactor.text(workspaceId, 160))
        .put("cwd", AgentRedactor.text(cwd, 512))
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("timeout_ms", timeoutMs.coerceIn(1_000L, 120_000L))
        .put(
            "env_keys",
            JSONArray().apply {
                env.keys.map { it.trim() }.filter { it.isNotBlank() }
                    .sorted().take(16).forEach { put(AgentRedactor.text(it, 96)) }
            },
        )
}

data class InteractiveSessionState(
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val executable: String? = null,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val status: InteractiveSessionStatus = InteractiveSessionStatus.IDLE,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val summary: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("workspace_id", AgentRedactor.text(workspaceId, 160))
        .put("executable", AgentRedactor.text(executable, 96))
        .put(
            "args",
            JSONArray().apply {
                args.take(MAX_ARGS).forEach { put(AgentRedactor.text(it, 256)) }
            },
        )
        .put("cwd", AgentRedactor.text(cwd, 512))
        .put("status", status.name)
        .put("stdout", AgentRedactor.text(stdout, MAX_OUTPUT_CHARS))
        .put("stderr", AgentRedactor.text(stderr, MAX_OUTPUT_CHARS))
        .put("exit_code", exitCode)
        .put("duration_ms", durationMs)
        .put("summary", AgentRedactor.text(summary, MAX_SUMMARY_CHARS))
        .put("error_code", AgentRedactor.text(errorCode, 96))
        .put("updated_at", updatedAt)

    companion object {
        private const val MAX_ARGS = 32
        private const val MAX_OUTPUT_CHARS = 32_000
        private const val MAX_SUMMARY_CHARS = 2_000

        fun fromJson(value: JSONObject): InteractiveSessionState {
            val status = runCatching {
                InteractiveSessionStatus.valueOf(value.optString("status"))
            }.getOrDefault(InteractiveSessionStatus.UNKNOWN)
            val args = buildList {
                val array = value.optJSONArray("args") ?: JSONArray()
                for (index in 0 until array.length().coerceAtMost(MAX_ARGS)) {
                    val item = AgentRedactor.text(array.optString(index), 256)
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    if (item != null) add(item)
                }
            }
            return InteractiveSessionState(
                sessionId = safeText(value.optString("session_id"), 160),
                workspaceId = safeText(value.optString("workspace_id"), 160),
                executable = safeText(value.optString("executable"), 96),
                args = args,
                cwd = safeText(value.optString("cwd"), 512),
                status = status,
                stdout = safeText(value.optString("stdout"), MAX_OUTPUT_CHARS),
                stderr = safeText(value.optString("stderr"), MAX_OUTPUT_CHARS),
                exitCode = if (value.has("exit_code") && !value.isNull("exit_code")) {
                    value.optInt("exit_code")
                } else {
                    null
                },
                durationMs = if (value.has("duration_ms") && !value.isNull("duration_ms")) {
                    value.optLong("duration_ms").takeIf { it >= 0L }
                } else {
                    null
                },
                summary = safeText(value.optString("summary"), MAX_SUMMARY_CHARS),
                errorCode = safeText(value.optString("error_code"), 96),
                updatedAt = value.optLong("updated_at", System.currentTimeMillis()),
            )
        }

        private fun safeText(value: String?, maxChars: Int): String? =
            AgentRedactor.text(value, maxChars)
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
    }
}

enum class RuntimeStatus {
    EMPTY,
    INSTALLING,
    STARTING,
    READY,
    STOPPING,
    FAILED,
    ROLLBACK,
}

data class RuntimeState(
    val status: RuntimeStatus = RuntimeStatus.EMPTY,
    val sessionId: String? = null,
    val version: String? = null,
    val abi: String? = null,
    val checksum: String? = null,
    val heartbeatAt: Long? = null,
    val summary: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("version", AgentRedactor.text(version, 96))
        .put("abi", AgentRedactor.text(abi, 96))
        .put("checksum", AgentRedactor.text(checksum, 80))
        .put("heartbeat_at", heartbeatAt)
        .put("summary", AgentRedactor.text(summary, 2_000))
        .put("error_code", AgentRedactor.text(errorCode, 96))
        .put("updated_at", updatedAt)
}

enum class ActionsOperationStatus {
    IDLE,
    DISPATCHING,
    DISCOVERING_RUN,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

data class ActionsRunRequest(
    val token: String = "",
    val repository: String,
    val workflow: String,
    val ref: String = "main",
    val sessionId: String? = null,
    val expectedCommitSha: String? = null,
    val inputs: Map<String, String> = emptyMap(),
    val pollTimeoutMs: Long = 5 * 60 * 1_000L,
    val pollIntervalMs: Long = 1_500L,
) {
    fun toAuditJson(): JSONObject = JSONObject()
        .put("repository", AgentRedactor.text(repository, 160))
        .put("workflow", AgentRedactor.text(workflow, 160))
        .put("ref", AgentRedactor.text(ref, 160))
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("expected_commit_sha", AgentRedactor.text(expectedCommitSha, 80))
        .put(
            "inputs",
            JSONObject().apply {
                inputs.entries
                    .sortedBy { it.key }
                    .take(16)
                    .forEach { (key, value) ->
                        put(
                            AgentRedactor.text(key, 96) ?: "input",
                            AgentRedactor.text(value, 1_000),
                        )
                    }
            },
        )
}

data class ActionsStepState(
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val number: Int = 0,
    val durationMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", AgentRedactor.text(name, 200))
        .put("status", AgentRedactor.text(status, 64))
        .put("conclusion", AgentRedactor.text(conclusion, 64))
        .put("number", number)
        .put("duration_ms", durationMs)
}

data class ActionsJobState(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val durationMs: Long? = null,
    val steps: List<ActionsStepState> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", AgentRedactor.text(name, 200))
        .put("status", AgentRedactor.text(status, 64))
        .put("conclusion", AgentRedactor.text(conclusion, 64))
        .put("duration_ms", durationMs)
        .put(
            "steps",
            JSONArray().apply {
                steps.take(MAX_STEPS).forEach { put(it.toJson()) }
            },
        )

    private companion object {
        const val MAX_STEPS = 64
    }
}

data class ActionsArtifactState(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val contentType: String? = null,
    val expired: Boolean = false,
    val archiveDigest: String? = null,
    val sourceSha: String? = null,
    val checksum: String? = null,
    val verified: Boolean = false,
    val savedPath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", AgentRedactor.text(name, 200))
        .put("size_bytes", sizeBytes)
        .put("content_type", AgentRedactor.text(contentType, 160))
        .put("expired", expired)
        .put("archive_digest", AgentRedactor.text(archiveDigest, 160))
        .put("source_sha", AgentRedactor.text(sourceSha, 80))
        .put("checksum", AgentRedactor.text(checksum, 80))
        .put("verified", verified)
        .put("saved_path", AgentRedactor.text(savedPath, 260))
}

data class ActionsOperationState(
    val sessionId: String? = null,
    val repository: String? = null,
    val workflow: String? = null,
    val ref: String? = null,
    val status: ActionsOperationStatus = ActionsOperationStatus.IDLE,
    val runId: Long? = null,
    val runNumber: Int? = null,
    val headSha: String? = null,
    val conclusion: String? = null,
    val failedStep: String? = null,
    val jobs: List<ActionsJobState> = emptyList(),
    val artifacts: List<ActionsArtifactState> = emptyList(),
    val redactedLogs: String? = null,
    val summary: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("repository", AgentRedactor.text(repository, 160))
        .put("workflow", AgentRedactor.text(workflow, 160))
        .put("ref", AgentRedactor.text(ref, 160))
        .put("status", status.name)
        .put("run_id", runId)
        .put("run_number", runNumber)
        .put("head_sha", AgentRedactor.text(headSha, 80))
        .put("conclusion", AgentRedactor.text(conclusion, 64))
        .put("failed_step", AgentRedactor.text(failedStep, 320))
        .put(
            "jobs",
            JSONArray().apply {
                jobs.take(MAX_JOBS).forEach { put(it.toJson()) }
            },
        )
        .put(
            "artifacts",
            JSONArray().apply {
                artifacts.take(MAX_ARTIFACTS).forEach { put(it.toJson()) }
            },
        )
        .put("redacted_logs", AgentRedactor.text(redactedLogs, MAX_LOG_CHARS))
        .put("summary", AgentRedactor.text(summary, MAX_SUMMARY_CHARS))
        .put("error_code", AgentRedactor.text(errorCode, 96))
        .put("updated_at", updatedAt)

    companion object {
        private const val MAX_JOBS = 32
        private const val MAX_ARTIFACTS = 32
        private const val MAX_STEPS = 64
        private const val MAX_LOG_CHARS = 32_000
        private const val MAX_SUMMARY_CHARS = 2_000
        private val SHA_PATTERN = Regex("[A-Fa-f0-9]{40,64}")

        fun fromJson(value: JSONObject): ActionsOperationState {
            val status = runCatching {
                ActionsOperationStatus.valueOf(value.optString("status"))
            }.getOrDefault(ActionsOperationStatus.UNKNOWN)
            val jobs = buildList {
                val array = value.optJSONArray("jobs") ?: JSONArray()
                for (index in 0 until array.length().coerceAtMost(MAX_JOBS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optLong("id", 0L)
                    if (id <= 0L) continue
                    val steps = buildList {
                        val stepsArray = item.optJSONArray("steps") ?: JSONArray()
                        for (stepIndex in 0 until stepsArray.length()
                            .coerceAtMost(MAX_STEPS)
                        ) {
                            val step = stepsArray.optJSONObject(stepIndex) ?: continue
                            val name = safeText(step.optString("name"), 200)
                                ?: continue
                            add(
                                ActionsStepState(
                                    name = name,
                                    status = safeText(
                                        step.optString("status"),
                                        64,
                                    ).orEmpty(),
                                    conclusion = safeText(
                                        step.optString("conclusion"),
                                        64,
                                    ),
                                    number = step.optInt("number", 0),
                                    durationMs = optionalLong(step, "duration_ms"),
                                ),
                            )
                        }
                    }
                    add(
                        ActionsJobState(
                            id = id,
                            name = safeText(item.optString("name"), 200).orEmpty(),
                            status = safeText(item.optString("status"), 64).orEmpty(),
                            conclusion = safeText(item.optString("conclusion"), 64),
                            durationMs = optionalLong(item, "duration_ms"),
                            steps = steps,
                        ),
                    )
                }
            }
            val artifacts = buildList {
                val array = value.optJSONArray("artifacts") ?: JSONArray()
                for (index in 0 until array.length().coerceAtMost(MAX_ARTIFACTS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optLong("id", 0L)
                    if (id <= 0L) continue
                    val name = safeText(item.optString("name"), 200)
                        ?: continue
                    add(
                        ActionsArtifactState(
                            id = id,
                            name = name,
                            sizeBytes = item.optLong("size_bytes", 0L)
                                .coerceAtLeast(0L),
                            contentType = safeText(
                                item.optString("content_type"),
                                160,
                            ),
                            expired = item.optBoolean("expired", false),
                            archiveDigest = safeText(
                                item.optString("archive_digest"),
                                160,
                            ),
                            sourceSha = safeSha(item.optString("source_sha")),
                            checksum = safeSha(item.optString("checksum")),
                            verified = item.optBoolean("verified", false),
                            savedPath = safeText(
                                item.optString("saved_path"),
                                260,
                            ),
                        ),
                    )
                }
            }
            return ActionsOperationState(
                sessionId = safeText(value.optString("session_id"), 160),
                repository = safeText(value.optString("repository"), 160),
                workflow = safeText(value.optString("workflow"), 160),
                ref = safeText(value.optString("ref"), 160),
                status = status,
                runId = optionalLong(value, "run_id"),
                runNumber = value.optInt("run_number", 0).takeIf { it > 0 },
                headSha = safeSha(value.optString("head_sha")),
                conclusion = safeText(value.optString("conclusion"), 64),
                failedStep = safeText(value.optString("failed_step"), 320),
                jobs = jobs,
                artifacts = artifacts,
                redactedLogs = safeText(
                    value.optString("redacted_logs"),
                    MAX_LOG_CHARS,
                ),
                summary = safeText(value.optString("summary"), MAX_SUMMARY_CHARS),
                errorCode = safeText(value.optString("error_code"), 96),
                updatedAt = value.optLong(
                    "updated_at",
                    System.currentTimeMillis(),
                ),
            )
        }

        private fun optionalLong(value: JSONObject, key: String): Long? {
            if (!value.has(key) || value.isNull(key)) return null
            return value.optLong(key, Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE || it <= 0L }
        }

        private fun safeText(value: String?, maxChars: Int): String? {
            return AgentRedactor.text(value, maxChars)
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
        }

        private fun safeSha(value: String?): String? {
            return safeText(value, 80)?.takeIf { SHA_PATTERN.matches(it) }
        }
    }
}

data class ActionsArtifactRequest(
    val token: String = "",
    val repository: String,
    val runId: Long,
    val artifactId: Long,
    val expectedCommitSha: String,
    val workspaceId: String? = null,
    val outputName: String? = null,
)

enum class ActionsArtifactSaveStatus {
    VERIFIED_SAVED,
    FAILED,
    UNKNOWN,
}

data class ActionsArtifactSaveResult(
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val artifactId: Long? = null,
    val fileName: String? = null,
    val relativePath: String? = null,
    val status: ActionsArtifactSaveStatus,
    val summary: String,
    val sourceSha: String? = null,
    val checksum: String? = null,
    val workspaceFingerprintBefore: String? = null,
    val workspaceFingerprintAfter: String? = null,
    val errorCode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", AgentRedactor.text(sessionId, 160))
        .put("workspace_id", AgentRedactor.text(workspaceId, 160))
        .put("artifact_id", artifactId)
        .put("file_name", AgentRedactor.text(fileName, 200))
        .put("relative_path", AgentRedactor.text(relativePath, 260))
        .put("status", status.name)
        .put("summary", AgentRedactor.text(summary, 2_000))
        .put("source_sha", AgentRedactor.text(sourceSha, 80))
        .put("checksum", AgentRedactor.text(checksum, 80))
        .put(
            "workspace_fingerprint_before",
            AgentRedactor.text(workspaceFingerprintBefore, 80),
        )
        .put(
            "workspace_fingerprint_after",
            AgentRedactor.text(workspaceFingerprintAfter, 80),
        )
        .put("error_code", AgentRedactor.text(errorCode, 96))
}


enum class PatchRecoveryStatus {
    APPLIED,
    ROLLED_BACK,
    UNKNOWN,
}

data class PatchRecoveryState(
    val sessionId: String,
    val workspaceId: String,
    val operationId: String,
    val path: String,
    val status: PatchRecoveryStatus,
    val workspaceFingerprintBefore: String,
    val workspaceFingerprintAfter: String?,
    val oldSha256: String?,
    val newSha256: String,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("session_id", sessionId)
        .put("workspace_id", workspaceId)
        .put("operation_id", operationId)
        .put("path", path)
        .put("status", status.name)
        .put("workspace_fingerprint_before", workspaceFingerprintBefore)
        .put("workspace_fingerprint_after", workspaceFingerprintAfter)
        .put("old_sha256", oldSha256)
        .put("new_sha256", newSha256)
        .put("error_code", errorCode)
        .put("updated_at", updatedAt)

    companion object {
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
        private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")

        fun fromJson(value: JSONObject): PatchRecoveryState? {
            val sessionId = value.optString("session_id").trim()
            val workspaceId = value.optString("workspace_id").trim()
            val operationId = value.optString("operation_id").trim()
            val path = value.optString("path").trim()
            val beforeFingerprint = value.optString("workspace_fingerprint_before").trim()
            val afterFingerprint = value.optString("workspace_fingerprint_after")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val oldSha = value.optString("old_sha256")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val newSha = value.optString("new_sha256").trim()
            if (
                !IDENTIFIER_PATTERN.matches(sessionId) ||
                !IDENTIFIER_PATTERN.matches(workspaceId) ||
                !IDENTIFIER_PATTERN.matches(operationId) ||
                path.isBlank() ||
                path.length > 512 ||
                path.contains('\u0000') ||
                !SHA256_PATTERN.matches(beforeFingerprint) ||
                (afterFingerprint != null && !SHA256_PATTERN.matches(afterFingerprint)) ||
                (oldSha != null && !SHA256_PATTERN.matches(oldSha)) ||
                !SHA256_PATTERN.matches(newSha)
            ) {
                return null
            }
            val parsedStatus = runCatching {
                PatchRecoveryStatus.valueOf(value.optString("status"))
            }.getOrNull() ?: PatchRecoveryStatus.UNKNOWN
            val safeStatus = if (
                parsedStatus == PatchRecoveryStatus.APPLIED &&
                afterFingerprint == null
            ) {
                PatchRecoveryStatus.UNKNOWN
            } else {
                parsedStatus
            }
            return PatchRecoveryState(
                sessionId = sessionId,
                workspaceId = workspaceId,
                operationId = operationId,
                path = path,
                status = safeStatus,
                workspaceFingerprintBefore = beforeFingerprint,
                workspaceFingerprintAfter = afterFingerprint,
                oldSha256 = oldSha,
                newSha256 = newSha,
                errorCode = value.optString("error_code")
                    .trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.take(160),
                updatedAt = value.optLong("updated_at", System.currentTimeMillis()),
            )
        }
    }
}

enum class PatchRollbackStatus {
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

data class PatchRollbackResult(
    val operationId: String? = null,
    val path: String? = null,
    val status: PatchRollbackStatus,
    val summary: String,
    val workspaceFingerprintBefore: String? = null,
    val workspaceFingerprintAfter: String? = null,
    val errorCode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("operation_id", operationId)
        .put("path", path)
        .put("status", status.name)
        .put("summary", summary)
        .put("workspace_fingerprint_before", workspaceFingerprintBefore)
        .put("workspace_fingerprint_after", workspaceFingerprintAfter)
        .put("error_code", errorCode)
}


internal object AgentRedactor {
    private val dataUrlPattern = Regex(
        """data:[^;\s]+;base64,[A-Za-z0-9+/=]+""",
        RegexOption.IGNORE_CASE,
    )
    private val secretFieldPattern = Regex(
        """(?i)("?(?:authorization|cookie|token|api[_-]?key|password|secret)"?\s*:\s*)("[^"]*"|'[^']*'|[^,\s}]+)""",
    )
    private val secretAssignmentPattern = Regex(
        """(?i)(\b(?:authorization|cookie|token|api[_-]?key|password|secret)\s*[=:]\s*)([^\s,;]+)""",
    )
    private val bearerPattern = Regex(
        """(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*""",
    )
    private val knownTokenPattern = Regex(
        """\b(?:ghp_|github_pat_|sk-)[A-Za-z0-9_-]{8,}\b""",
    )

    fun text(value: String?, maxChars: Int): String? {
        if (value == null) return null
        val limit = maxChars.coerceAtLeast(1)
        var result = value
        result = result.replace(dataUrlPattern, "<redacted-data-url>")
        result = result.replace(bearerPattern, "Bearer <redacted>")
        result = result.replace(knownTokenPattern, "<redacted-token>")
        result = result.replace(secretFieldPattern) { match ->
            match.groupValues[1] + "\"<redacted>\""
        }
        result = result.replace(secretAssignmentPattern) { match ->
            match.groupValues[1] + "<redacted>"
        }
        return if (result.length <= limit) {
            result
        } else {
            result.take(limit) + "\n[truncated]"
        }
    }
}
