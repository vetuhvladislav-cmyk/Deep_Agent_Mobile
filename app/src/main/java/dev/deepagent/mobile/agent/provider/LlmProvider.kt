package dev.deepagent.mobile.agent.provider

import dev.deepagent.mobile.agent.deepseek.DeepSeekRequest
import dev.deepagent.mobile.agent.deepseek.DeepSeekResponsesClient
import dev.deepagent.mobile.agent.deepseek.DeepSeekRoundResult
import dev.deepagent.mobile.agent.deepseek.DeepSeekStreamEvent

/**
 * Provider boundary owned by Agent Core.
 *
 * The request remains typed to the current Responses contract; arbitrary
 * OpenAI-compatible endpoints are not implied by this interface.
 */
interface LlmProvider {
    val id: String

    suspend fun streamRound(
        request: DeepSeekRequest,
        onEvent: (DeepSeekStreamEvent) -> Unit,
    ): DeepSeekRoundResult

    fun cancel()
}

class DeepSeekLlmProvider(
    private val client: DeepSeekResponsesClient = DeepSeekResponsesClient(),
) : LlmProvider {
    override val id: String = ID

    override suspend fun streamRound(
        request: DeepSeekRequest,
        onEvent: (DeepSeekStreamEvent) -> Unit,
    ): DeepSeekRoundResult = client.streamRound(request, onEvent)

    override fun cancel() {
        client.cancelActive()
    }

    companion object {
        const val ID = "deepseek.responses"
    }
}

class ProviderRegistry(
    providers: Collection<LlmProvider>,
) {
    private val providersById = providers
        .onEach { require(it.id.isNotBlank()) { "Provider ID не задан" } }
        .associateBy { it.id }

    init {
        require(providersById.size == providers.size) {
            "Provider IDs должны быть уникальными"
        }
    }

    fun resolve(providerId: String): LlmProvider? {
        return providersById[providerId.trim()]
    }

    fun ids(): Set<String> = providersById.keys

    fun cancelAll() {
        providersById.values.forEach { provider ->
            runCatching { provider.cancel() }
        }
    }
}
