package dev.deepagent.mobile.agent.provider

import dev.deepagent.mobile.agent.deepseek.DeepSeekRequest
import dev.deepagent.mobile.agent.deepseek.DeepSeekRoundResult
import dev.deepagent.mobile.agent.deepseek.DeepSeekStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {

    @Test
    fun resolvesOnlyRegisteredProviderAndCancelsAll() {
        val provider = FakeProvider("fake")
        val registry = ProviderRegistry(listOf(provider))

        assertEquals(provider, registry.resolve("fake"))
        assertEquals(provider, registry.resolve(" fake "))
        assertNull(registry.resolve("unregistered"))

        registry.cancelAll()

        assertTrue(provider.cancelled)
    }

    @Test
    fun duplicateProviderIdsAreRejected() {
        var rejected = false
        try {
            ProviderRegistry(listOf(FakeProvider("same"), FakeProvider("same")))
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private class FakeProvider(
        override val id: String,
    ) : LlmProvider {
        var cancelled = false

        override suspend fun streamRound(
            request: DeepSeekRequest,
            onEvent: (DeepSeekStreamEvent) -> Unit,
        ): DeepSeekRoundResult {
            return DeepSeekRoundResult(
                response = null,
                functionCalls = emptyList(),
            )
        }

        override fun cancel() {
            cancelled = true
        }
    }
}
