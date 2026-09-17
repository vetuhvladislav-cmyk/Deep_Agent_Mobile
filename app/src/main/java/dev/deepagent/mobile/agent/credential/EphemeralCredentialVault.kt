package dev.deepagent.mobile.agent.credential

import dev.deepagent.mobile.agent.model.CredentialState

enum class CredentialKind {
    DEEPSEEK_API_KEY,
    GITHUB_TOKEN,
}

/**
 * Process-memory-only credential holder.
 *
 * Values are kept in wipeable char arrays, expire after a short idle window and
 * are never serialized, logged or exposed through the public state.
 */
class EphemeralCredentialVault(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val lock = Any()
    private var deepSeekApiKey: CharArray? = null
    private var githubToken: CharArray? = null
    private var expiresAtMs: Long = 0L
    private var updatedAt: Long = System.currentTimeMillis()

    fun replace(
        deepSeekApiKey: String?,
        githubToken: String?,
    ): CredentialState = synchronized(lock) {
        clearLocked()
        this.deepSeekApiKey = normalize(deepSeekApiKey)
        this.githubToken = normalize(githubToken)
        if (this.deepSeekApiKey != null || this.githubToken != null) {
            expiresAtMs = System.currentTimeMillis() + ttlMs.coerceAtLeast(1L)
        }
        updatedAt = System.currentTimeMillis()
        stateLocked()
    }

    fun read(kind: CredentialKind): String? = synchronized(lock) {
        expireIfNeededLocked()
        when (kind) {
            CredentialKind.DEEPSEEK_API_KEY -> deepSeekApiKey
            CredentialKind.GITHUB_TOKEN -> githubToken
        }?.let { chars -> String(chars) }
    }

    fun state(): CredentialState = synchronized(lock) {
        expireIfNeededLocked()
        stateLocked()
    }

    fun clearAll(): CredentialState = synchronized(lock) {
        clearLocked()
        updatedAt = System.currentTimeMillis()
        stateLocked()
    }

    private fun stateLocked(): CredentialState {
        return CredentialState(
            deepSeekConfigured = deepSeekApiKey != null,
            githubConfigured = githubToken != null,
            updatedAt = updatedAt,
        )
    }

    private fun expireIfNeededLocked() {
        if (expiresAtMs != 0L && System.currentTimeMillis() >= expiresAtMs) {
            clearLocked()
            updatedAt = System.currentTimeMillis()
        }
    }

    private fun clearLocked() {
        deepSeekApiKey?.fill('\u0000')
        githubToken?.fill('\u0000')
        deepSeekApiKey = null
        githubToken = null
        expiresAtMs = 0L
    }

    private fun normalize(value: String?): CharArray? {
        return value
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_CREDENTIAL_CHARS }
            ?.toCharArray()
    }

    private companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1_000L
        const val MAX_CREDENTIAL_CHARS = 4_096
    }
}
