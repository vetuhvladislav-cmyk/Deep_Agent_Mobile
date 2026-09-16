package dev.deepagent.mobile.agent.session

import android.content.Context
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRedactor
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class SessionRequestSummary(
    val task: String,
    val target: ExecutionTarget,
    val permission: PermissionMode,
    val workspaceId: String?,
    val repository: String?,
    val workflow: String?,
    val ref: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("task", AgentRedactor.text(task, MAX_TASK_CHARS))
        .put("target", target.name)
        .put("permission", permission.name)
        .put("workspace_id", AgentRedactor.text(workspaceId, MAX_IDENTIFIER_CHARS))
        .put("repository", AgentRedactor.text(repository, MAX_IDENTIFIER_CHARS))
        .put("workflow", AgentRedactor.text(workflow, MAX_IDENTIFIER_CHARS))
        .put("ref", AgentRedactor.text(ref, MAX_IDENTIFIER_CHARS))

    companion object {
        const val MAX_TASK_CHARS = 8_000
        const val MAX_IDENTIFIER_CHARS = 160

        fun fromJson(value: JSONObject): SessionRequestSummary {
            return SessionRequestSummary(
                task = AgentRedactor.text(value.optString("task"), MAX_TASK_CHARS).orEmpty(),
                target = enumOrDefault(
                    value.optString("target"),
                    ExecutionTarget.AUTO,
                ),
                permission = enumOrDefault(
                    value.optString("permission"),
                    PermissionMode.READ_ONLY,
                ),
                workspaceId = AgentRedactor.text(
                    value.optString("workspace_id"),
                    MAX_IDENTIFIER_CHARS,
                )?.takeIf { it.isNotBlank() },
                repository = AgentRedactor.text(
                    value.optString("repository"),
                    MAX_IDENTIFIER_CHARS,
                )?.takeIf { it.isNotBlank() },
                workflow = AgentRedactor.text(
                    value.optString("workflow"),
                    MAX_IDENTIFIER_CHARS,
                )?.takeIf { it.isNotBlank() },
                ref = AgentRedactor.text(
                    value.optString("ref").ifBlank { "main" },
                    MAX_IDENTIFIER_CHARS,
                ).orEmpty(),
            )
        }
    }
}

data class SessionInvocationRecord(
    val invocationId: String,
    val toolName: String,
    val callId: String? = null,
    val state: String = "RUNNING",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val summary: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("invocation_id", invocationId)
        .put("tool_name", AgentRedactor.text(toolName, MAX_TOOL_NAME_CHARS))
        .put("call_id", AgentRedactor.text(callId, MAX_IDENTIFIER_CHARS))
        .put("state", state.take(MAX_STATE_CHARS))
        .put("started_at", startedAt)
        .put("completed_at", completedAt)
        .put("summary", AgentRedactor.text(summary, MAX_SUMMARY_CHARS))

    companion object {
        const val MAX_TOOL_NAME_CHARS = 160
        const val MAX_IDENTIFIER_CHARS = 160
        const val MAX_STATE_CHARS = 64
        const val MAX_SUMMARY_CHARS = 2_000

        fun fromJson(value: JSONObject): SessionInvocationRecord? {
            val invocationId = value.optString("invocation_id").trim()
            val toolName = value.optString("tool_name").trim()
            if (!isValidJournalIdentifier(invocationId) || toolName.isBlank()) {
                return null
            }
            return SessionInvocationRecord(
                invocationId = invocationId,
                toolName = AgentRedactor.text(toolName, MAX_TOOL_NAME_CHARS).orEmpty(),
                callId = AgentRedactor.text(
                    value.optString("call_id"),
                    MAX_IDENTIFIER_CHARS,
                )?.takeIf { it.isNotBlank() },
                state = value.optString("state")
                    .ifBlank { "UNKNOWN" }
                    .take(MAX_STATE_CHARS),
                startedAt = value.optLongOrNull("started_at") ?: 0L,
                completedAt = value.optLongOrNull("completed_at"),
                summary = AgentRedactor.text(
                    value.optString("summary"),
                    MAX_SUMMARY_CHARS,
                )?.takeIf { it.isNotBlank() },
            )
        }
    }
}

data class SessionDecisionRecord(
    val decisionId: String,
    val kind: String,
    val state: String,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("decision_id", decisionId)
        .put("kind", AgentRedactor.text(kind, MAX_KIND_CHARS))
        .put("state", state.take(MAX_STATE_CHARS))
        .put("detail", AgentRedactor.text(detail, MAX_DETAIL_CHARS))
        .put("created_at", createdAt)

    companion object {
        const val MAX_KIND_CHARS = 96
        const val MAX_STATE_CHARS = 64
        const val MAX_DETAIL_CHARS = 2_000

        fun fromJson(value: JSONObject): SessionDecisionRecord? {
            val decisionId = value.optString("decision_id").trim()
            val kind = value.optString("kind").trim()
            if (!isValidJournalIdentifier(decisionId) || kind.isBlank()) {
                return null
            }
            return SessionDecisionRecord(
                decisionId = decisionId,
                kind = AgentRedactor.text(kind, MAX_KIND_CHARS).orEmpty(),
                state = value.optString("state")
                    .ifBlank { "UNKNOWN" }
                    .take(MAX_STATE_CHARS),
                detail = AgentRedactor.text(
                    value.optString("detail"),
                    MAX_DETAIL_CHARS,
                )?.takeIf { it.isNotBlank() },
                createdAt = value.optLongOrNull("created_at")
                    ?: System.currentTimeMillis(),
            )
        }
    }
}

data class PersistedAgentSession(
    val sessionId: String,
    val request: SessionRequestSummary,
    val state: AgentSessionState,
    val events: List<AgentEvent>,
    val updatedAt: Long,
    val eventCursor: Long = events.maxOfOrNull { it.sequence }
        ?: events.size.toLong(),
    val invocations: List<SessionInvocationRecord> = emptyList(),
    val decisions: List<SessionDecisionRecord> = emptyList(),
    val recoveryReason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", VERSION)
        .put("version", VERSION)
        .put("session_id", sessionId)
        .put("request", request.toJson())
        .put(
            "state",
            JSONObject()
                .put("status", state.status.name)
                .put("session_id", state.sessionId ?: sessionId)
                .put("workspace_id", state.workspaceId)
                .put("target", state.target?.name)
                .put("task", AgentRedactor.text(state.task, SessionRequestSummary.MAX_TASK_CHARS))
                .put("started_at", state.startedAt)
                .put("finished_at", state.finishedAt)
                .put("last_error", AgentRedactor.text(state.lastError, MAX_ERROR_CHARS))
                .put("event_cursor", eventCursor)
                .put("recovery_required", state.recoveryRequired),
        )
        .put(
            "events",
            JSONArray().apply {
                events.takeLast(MAX_EVENTS).forEach { event ->
                    put(event.toJournalJson())
                }
            },
        )
        .put(
            "invocations",
            JSONArray().apply {
                invocations.takeLast(MAX_INVOCATIONS).forEach { invocation ->
                    put(invocation.toJson())
                }
            },
        )
        .put(
            "decisions",
            JSONArray().apply {
                decisions.takeLast(MAX_DECISIONS).forEach { decision ->
                    put(decision.toJson())
                }
            },
        )
        .put("recovery_reason", AgentRedactor.text(recoveryReason, MAX_ERROR_CHARS))
        .put("event_cursor", eventCursor)
        .put("updated_at", updatedAt)

    companion object {
        const val VERSION = 2
        const val MAX_EVENTS = 500
        const val MAX_INVOCATIONS = 500
        const val MAX_DECISIONS = 200
        const val MAX_ERROR_CHARS = 4_000
        const val MAX_EVENT_MESSAGE_CHARS = 1_000
        const val MAX_EVENT_DETAIL_CHARS = 4_000

        fun fromJson(value: JSONObject): PersistedAgentSession? {
            val version = value.optInt(
                "schema_version",
                value.optInt("version", 0),
            )
            if (version !in SUPPORTED_VERSIONS) return null

            val sessionId = value.optString("session_id").trim()
            val requestObject = value.optJSONObject("request") ?: return null
            val stateObject = value.optJSONObject("state") ?: return null
            if (!isValidJournalIdentifier(sessionId)) return null

            val events = buildList {
                val array = value.optJSONArray("events") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val eventId = AgentRedactor.text(
                        item.optString("event_id"),
                        MAX_EVENT_ID_CHARS,
                    )?.takeIf { isValidJournalIdentifier(it) }
                        ?: UUID.randomUUID().toString()
                    add(
                        AgentEvent(
                            kind = enumOrDefault(
                                item.optString("kind"),
                                AgentEventKind.INFO,
                            ),
                            message = AgentRedactor.text(
                                item.optString("message"),
                                MAX_EVENT_MESSAGE_CHARS,
                            ).orEmpty(),
                            detail = AgentRedactor.text(
                                item.optString("detail"),
                                MAX_EVENT_DETAIL_CHARS,
                            )?.takeIf { it.isNotBlank() },
                            createdAt = item.optLongOrNull("created_at")
                                ?: System.currentTimeMillis(),
                            sessionId = sessionId,
                            eventId = eventId,
                            sequence = item.optLong(
                                "sequence",
                                (index + 1).toLong(),
                            ).coerceAtLeast(1L),
                            workspaceId = AgentRedactor.text(
                                item.optString("workspace_id"),
                                SessionRequestSummary.MAX_IDENTIFIER_CHARS,
                            )?.takeIf { it.isNotBlank() },
                            invocationId = AgentRedactor.text(
                                item.optString("invocation_id"),
                                SessionRequestSummary.MAX_IDENTIFIER_CHARS,
                            )?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }.takeLast(MAX_EVENTS)

            val maxSequence = events.maxOfOrNull { it.sequence } ?: 0L
            val stateSessionId = stateObject.optString("session_id")
                .takeIf { it.isNotBlank() }
                ?: sessionId
            val state = AgentSessionState(
                status = enumOrDefault(
                    stateObject.optString("status"),
                    AgentSessionStatus.IDLE,
                ),
                target = stateObject.optString("target")
                    .takeIf { it.isNotBlank() }
                    ?.let { enumOrDefault(it, ExecutionTarget.AUTO) },
                task = AgentRedactor.text(
                    stateObject.optString("task"),
                    SessionRequestSummary.MAX_TASK_CHARS,
                )?.takeIf { it.isNotBlank() },
                startedAt = stateObject.optLongOrNull("started_at"),
                finishedAt = stateObject.optLongOrNull("finished_at"),
                lastError = AgentRedactor.text(
                    stateObject.optString("last_error"),
                    MAX_ERROR_CHARS,
                )?.takeIf { it.isNotBlank() },
                sessionId = stateSessionId,
                workspaceId = AgentRedactor.text(
                    stateObject.optString("workspace_id"),
                    SessionRequestSummary.MAX_IDENTIFIER_CHARS,
                )?.takeIf { it.isNotBlank() },
                eventCursor = maxOf(
                    value.optLong("event_cursor", 0L),
                    stateObject.optLong("event_cursor", 0L),
                    maxSequence,
                ),
                recoveryRequired = stateObject.optBoolean(
                    "recovery_required",
                    false,
                ),
            )

            val invocations = parseInvocations(value)
            val decisions = parseDecisions(value)

            return PersistedAgentSession(
                sessionId = sessionId,
                request = SessionRequestSummary.fromJson(requestObject),
                state = state,
                events = events,
                updatedAt = value.optLongOrNull("updated_at")
                    ?: System.currentTimeMillis(),
                eventCursor = state.eventCursor,
                invocations = invocations,
                decisions = decisions,
                recoveryReason = AgentRedactor.text(
                    value.optString("recovery_reason"),
                    MAX_ERROR_CHARS,
                )?.takeIf { it.isNotBlank() },
            )
        }

        private fun parseInvocations(
            value: JSONObject,
        ): List<SessionInvocationRecord> {
            val array = value.optJSONArray("invocations") ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)
                        ?.let { SessionInvocationRecord.fromJson(it) }
                        ?.let(::add)
                }
            }.take(MAX_INVOCATIONS)
        }

        private fun parseDecisions(
            value: JSONObject,
        ): List<SessionDecisionRecord> {
            val array = value.optJSONArray("decisions") ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)
                        ?.let { SessionDecisionRecord.fromJson(it) }
                        ?.let(::add)
                }
            }.take(MAX_DECISIONS)
        }
    }
}

class SessionStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "agent-sessions")
    private val latestPointer = File(directory, "latest")
    private val lock = Any()

    init {
        check(directory.mkdirs() || directory.isDirectory) {
            "Не удалось создать каталог журнала сессий"
        }
    }

    fun loadLatest(): PersistedAgentSession? = synchronized(lock) {
        val candidates = linkedSetOf<String>()
        readPointerLocked()?.let(candidates::add)

        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("session-") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                file.name
                    .removePrefix("session-")
                    .removeSuffix(".json")
                    .takeIf(::isValidJournalIdentifier)
            }
            ?.forEach(candidates::add)

        candidates
            .mapNotNull(::loadLocked)
            .maxWithOrNull(
                compareBy<PersistedAgentSession> { it.updatedAt }
                    .thenBy { it.sessionId },
            )
    }

    fun load(sessionId: String): PersistedAgentSession? = synchronized(lock) {
        if (!isValidJournalIdentifier(sessionId)) return@synchronized null
        loadLocked(sessionId)
    }

    fun save(session: PersistedAgentSession) = synchronized(lock) {
        val file = fileFor(session.sessionId)
        val payload = session.toJson().toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_BYTES) {
            "Журнал сессии превышает лимит размера"
        }
        writeAtomically(file, payload)
        writeAtomically(latestPointer, session.sessionId)
    }

    private fun readPointerLocked(): String? {
        if (!latestPointer.isFile || latestPointer.length() > MAX_POINTER_BYTES) {
            return null
        }
        return runCatching {
            latestPointer.readText(Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf(::isValidJournalIdentifier)
    }

    private fun loadLocked(sessionId: String): PersistedAgentSession? {
        val file = runCatching { fileFor(sessionId) }.getOrNull() ?: return null
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_JOURNAL_BYTES) {
            return null
        }
        return runCatching {
            PersistedAgentSession.fromJson(
                JSONObject(file.readText(Charsets.UTF_8)),
            )
        }.getOrNull()
    }

    private fun fileFor(sessionId: String): File {
        require(isValidJournalIdentifier(sessionId)) {
            "Недопустимый sessionId"
        }
        return File(directory, "session-" + sessionId + ".json")
    }

    private fun writeAtomically(target: File, value: String) {
        val temporary = File(
            target.parentFile,
            "." + target.name + "." + UUID.randomUUID() + ".tmp",
        )
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveAtomically(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val MAX_JOURNAL_BYTES = 4L * 1024L * 1024L
        const val MAX_POINTER_BYTES = 256L
        const val MAX_EVENT_ID_CHARS = 160
        val SUPPORTED_VERSIONS = setOf(1, 2)
        val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}

private fun AgentEvent.toJournalJson(): JSONObject = JSONObject()
    .put("event_id", eventId)
    .put("sequence", sequence)
    .put("kind", kind.name)
    .put("message", AgentRedactor.text(message, PersistedAgentSession.MAX_EVENT_MESSAGE_CHARS))
    .put("detail", AgentRedactor.text(detail, PersistedAgentSession.MAX_EVENT_DETAIL_CHARS))
    .put("created_at", createdAt)
    .put("session_id", sessionId)
    .put("workspace_id", workspaceId)
    .put("invocation_id", invocationId)

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }
}

private fun isValidJournalIdentifier(value: String): Boolean {
    return value.length <= 160 && IDENTIFIER_PATTERN.matches(value)
}

private inline fun <reified T : Enum<T>> enumOrDefault(
    value: String,
    fallback: T,
): T {
    return runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
