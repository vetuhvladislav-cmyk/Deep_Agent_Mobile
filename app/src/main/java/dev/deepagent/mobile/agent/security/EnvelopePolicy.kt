package dev.deepagent.mobile.agent.security

/**
 * Single policy owner for tool output crossing into model context.
 */
object EnvelopePolicy {
    fun untrusted(
        toolName: String,
        capability: ToolCapability,
        ok: Boolean,
        summary: String,
        content: String,
        truncated: Boolean,
        errorCode: String?,
        operationId: String? = null,
        sessionId: String? = null,
        canonicalArgsSha256: String? = null,
    ): ToolOutputEnvelope = ToolOutputEnvelope.untrusted(
        toolName = toolName,
        capability = capability,
        ok = ok,
        summary = summary,
        content = content,
        truncated = truncated,
        errorCode = errorCode,
        operationId = operationId,
        sessionId = sessionId,
        canonicalArgsSha256 = canonicalArgsSha256,
    )
}
