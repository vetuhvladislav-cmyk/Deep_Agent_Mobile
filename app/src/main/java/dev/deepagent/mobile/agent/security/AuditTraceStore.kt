package dev.deepagent.mobile.agent.security

import dev.deepagent.mobile.agent.model.AgentRedactor
import org.json.JSONArray
import org.json.JSONObject

data class AuditTraceRecord(
    val sessionId: String?,
    val toolName: String?,
    val operationId: String?,
    val state: String,
    val detail: String?,
    val createdAt: Long,
)

interface AuditTraceStore {
    fun append(
        sessionId: String?,
        toolName: String?,
        operationId: String?,
        state: String,
        detail: String?,
    )

    fun snapshot(): List<AuditTraceRecord>

    fun exportRedacted(): String
}

class InMemoryAuditTraceStore(
    private val maxRecords: Int = 500,
) : AuditTraceStore {
    private val records = ArrayDeque<AuditTraceRecord>()

    override fun append(
        sessionId: String?,
        toolName: String?,
        operationId: String?,
        state: String,
        detail: String?,
    ) {
        synchronized(records) {
            records.addLast(
                AuditTraceRecord(
                    sessionId = AgentRedactor.text(sessionId, 160),
                    toolName = AgentRedactor.text(toolName, 160),
                    operationId = AgentRedactor.text(operationId, 160),
                    state = AgentRedactor.text(state, 64).orEmpty(),
                    detail = AgentRedactor.text(detail, 4_000),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            while (records.size > maxRecords.coerceAtLeast(1)) {
                records.removeFirst()
            }
        }
    }

    override fun snapshot(): List<AuditTraceRecord> = synchronized(records) {
        records.toList()
    }

    override fun exportRedacted(): String {
        return JSONArray().apply {
            snapshot().forEach { record ->
                put(
                    JSONObject()
                        .put("session_id", record.sessionId)
                        .put("tool", record.toolName)
                        .put("operation_id", record.operationId)
                        .put("state", record.state)
                        .put("detail", record.detail)
                        .put("created_at", record.createdAt),
                )
            }
        }.toString()
    }
}
