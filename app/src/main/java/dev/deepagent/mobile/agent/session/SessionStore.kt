package dev.deepagent.mobile.agent.session

import android.content.Context
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        .put("task", task)
        .put("target", target.name)
        .put("permission", permission.name)
        .put("workspace_id", workspaceId)
        .put("repository", repository)
        .put("workflow", workflow)
        .put("ref", ref)

    companion object {
        fun fromJson(value: JSONObject): SessionRequestSummary {
            return SessionRequestSummary(
                task = value.optString("task"),
                target = enumOrDefault(
                    value.optString("target"),
                    ExecutionTarget.AUTO,
                ),
                permission = enumOrDefault(
                    value.optString("permission"),
                    PermissionMode.READ_ONLY,
                ),
                workspaceId = value.optString("workspace_id")
                    .takeIf { it.isNotBlank() },
                repository = value.optString("repository")
                    .takeIf { it.isNotBlank() },
                workflow = value.optString("workflow")
                    .takeIf { it.isNotBlank() },
                ref = value.optString("ref").ifBlank { "main" },
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("session_id", sessionId)
        .put("request", request.toJson())
        .put(
            "state",
            JSONObject()
                .put("status", state.status.name)
                .put("session_id", state.sessionId)
                .put("workspace_id", state.workspaceId)
                .put("target", state.target?.name)
                .put("task", state.task)
                .put("started_at", state.startedAt)
                .put("finished_at", state.finishedAt)
                .put("last_error", state.lastError),
        )
        .put(
            "events",
            JSONArray().apply {
                events.takeLast(MAX_EVENTS).forEach { event ->
                    put(
                        JSONObject()
                            .put("kind", event.kind.name)
                            .put("message", event.message)
                            .put("detail", event.detail)
                            .put("created_at", event.createdAt)
                            .put("session_id", event.sessionId),
                    )
                }
            },
        )
        .put("updated_at", updatedAt)

    companion object {
        const val VERSION = 1
        const val MAX_EVENTS = 500

        fun fromJson(value: JSONObject): PersistedAgentSession? {
            val sessionId = value.optString("session_id").trim()
            val requestObject = value.optJSONObject("request") ?: return null
            val stateObject = value.optJSONObject("state") ?: return null
            if (sessionId.isBlank()) return null

            val events = buildList {
                val array = value.optJSONArray("events") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        AgentEvent(
                            kind = enumOrDefault(
                                item.optString("kind"),
                                AgentEventKind.INFO,
                            ),
                            message = item.optString("message"),
                            detail = item.optString("detail")
                                .takeIf { it.isNotBlank() },
                            createdAt = item.optLong("created_at"),
                            sessionId = item.optString("session_id")
                                .takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }

            return PersistedAgentSession(
                sessionId = sessionId,
                request = SessionRequestSummary.fromJson(requestObject),
                state = AgentSessionState(
                    status = enumOrDefault(
                        stateObject.optString("status"),
                        AgentSessionStatus.IDLE,
                    ),
                    sessionId = stateObject.optString("session_id")
                        .takeIf { it.isNotBlank() },
                    workspaceId = stateObject.optString("workspace_id")
                        .takeIf { it.isNotBlank() },
                    target = stateObject.optString("target")
                        .takeIf { it.isNotBlank() }
                        ?.let { enumOrDefault(it, ExecutionTarget.AUTO) },
                    task = stateObject.optString("task")
                        .takeIf { it.isNotBlank() },
                    startedAt = stateObject.optLongOrNull("started_at"),
                    finishedAt = stateObject.optLongOrNull("finished_at"),
                    lastError = stateObject.optString("last_error")
                        .takeIf { it.isNotBlank() },
                ),
                events = events.takeLast(MAX_EVENTS),
                updatedAt = value.optLong("updated_at"),
            )
        }
    }
}

class SessionStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "agent-sessions")
    private val latestPointer = File(directory, "latest")

    init {
        directory.mkdirs()
    }

    fun loadLatest(): PersistedAgentSession? {
        val sessionId = runCatching {
            latestPointer.readText(Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val file = fileFor(sessionId)
        if (!file.isFile) return null
        return runCatching {
            PersistedAgentSession.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun save(session: PersistedAgentSession) {
        directory.mkdirs()
        val file = fileFor(session.sessionId)
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(session.toJson().toString(), Charsets.UTF_8)
        check(temporary.renameTo(file)) { "Не удалось сохранить сессию" }
        latestPointer.writeText(session.sessionId, Charsets.UTF_8)
    }

    private fun fileFor(sessionId: String): File {
        require(sessionId.matches(SESSION_ID_PATTERN)) {
            "Недопустимый sessionId"
        }
        return File(directory, "session-" + sessionId + ".json")
    }

    private companion object {
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9-]{8,80}")
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key).takeIf { it != 0L }
}

private inline fun <reified T : Enum<T>> enumOrDefault(
    value: String,
    fallback: T,
): T {
    return runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
