package dev.deepagent.mobile.agent.deepseek

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class DeepSeekImage(
    val dataUrl: String,
    val detail: String = "auto",
)

data class DeepSeekRequest(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val task: String,
    val image: DeepSeekImage? = null,
    val reasoningEffort: String = "high",
    val maxOutputTokens: Int = 4096,
)

sealed interface DeepSeekStreamEvent {
    data class ReasoningDelta(val text: String) : DeepSeekStreamEvent
    data class OutputDelta(val text: String) : DeepSeekStreamEvent
    data class ToolArgumentsDelta(val text: String) : DeepSeekStreamEvent
    data class Completed(val response: JSONObject?) : DeepSeekStreamEvent
    data class Failed(val message: String) : DeepSeekStreamEvent
}

/**
 * Минимальный Responses API клиент без дополнительной сетевой зависимости.
 *
 * Поддерживает semantic SSE события Responses API. История и последующие
 * tool rounds остаются ответственностью Agent Core, поскольку DeepSeek API
 * stateless.
 */
class DeepSeekResponsesClient {

    suspend fun stream(
        request: DeepSeekRequest,
        onEvent: (DeepSeekStreamEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(request.apiKey.isNotBlank()) { "DeepSeek API key is empty" }
        require(request.model.isNotBlank()) { "DeepSeek model is empty" }

        val endpoint = request.baseUrl.trimEnd('/') + "/responses"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 0
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${request.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }

        try {
            val body = buildRequestBody(request).toString()
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.take(2_000)
                    .orEmpty()
                throw IOException(
                    "DeepSeek HTTP $status${if (errorBody.isBlank()) "" else ": $errorBody"}",
                )
            }

            connection.inputStream.bufferedReader().use { reader ->
                var eventName: String? = null
                val data = StringBuilder()

                fun dispatchEvent() {
                    val currentEvent = eventName
                    val payload = data.toString().trim()
                    eventName = null
                    data.clear()

                    if (currentEvent.isNullOrBlank() || payload.isBlank()) return

                    val json = runCatching { JSONObject(payload) }.getOrNull()
                    when (currentEvent) {
                        "response.reasoning_text.delta" -> {
                            json?.optString("delta")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { onEvent(DeepSeekStreamEvent.ReasoningDelta(it)) }
                        }

                        "response.output_text.delta" -> {
                            json?.optString("delta")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { onEvent(DeepSeekStreamEvent.OutputDelta(it)) }
                        }

                        "response.function_call_arguments.delta",
                        "response.custom_tool_call_input.delta",
                        -> {
                            json?.optString("delta")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { onEvent(DeepSeekStreamEvent.ToolArgumentsDelta(it)) }
                        }

                        "response.completed",
                        "response.incomplete",
                        -> onEvent(DeepSeekStreamEvent.Completed(json?.optJSONObject("response")))

                        "response.failed" -> {
                            val responseError = json
                                ?.optJSONObject("response")
                                ?.optJSONObject("error")
                                ?.optString("message")
                            onEvent(
                                DeepSeekStreamEvent.Failed(
                                    responseError?.takeIf { it.isNotBlank() }
                                        ?: "DeepSeek response failed",
                                ),
                            )
                        }
                    }
                }

                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> {
                            eventName = line.removePrefix("event:").trim()
                        }

                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }

                        line.isBlank() -> dispatchEvent()
                    }
                }

                dispatchEvent()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(request: DeepSeekRequest): JSONObject {
        val content = JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "input_text")
                    .put("text", request.task),
            )
            request.image?.let {
                put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", it.dataUrl)
                        .put("detail", it.detail),
                )
            }
        }

        return JSONObject()
            .put("model", request.model)
            .put(
                "instructions",
                "You are the Agent Core inside a single Android APK. " +
                    "Return an actionable engineering plan and concise result. " +
                    "Do not claim that a tool was executed unless the host reports its result.",
            )
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ),
            )
            .put("stream", true)
            .put("max_output_tokens", request.maxOutputTokens)
            .put(
                "reasoning",
                JSONObject().put("effort", request.reasoningEffort),
            )
    }
}
