package dev.harness.mobile.agent.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubActionsRequest(
    val token: String,
    val repository: String,
    val workflow: String,
    val ref: String = "main",
    val inputs: Map<String, String> = emptyMap(),
)

sealed interface GitHubActionsResult {
    data object Dispatched : GitHubActionsResult
    data class NotConfigured(val message: String) : GitHubActionsResult
    data class Failed(val message: String) : GitHubActionsResult
}

/**
 * Узкий коннектор удалённой компиляции. Он запускает workflow, но не хранит
 * GitHub token и не пишет его в логи.
 */
class GitHubActionsClient {

    suspend fun dispatch(request: GitHubActionsRequest): GitHubActionsResult =
        withContext(Dispatchers.IO) {
            if (request.token.isBlank()) {
                return@withContext GitHubActionsResult.NotConfigured(
                    "GitHub token не задан",
                )
            }

            val repository = request.repository.trim()
            val parts = repository.split('/')
            if (parts.size != 2 || parts.any { it.isBlank() }) {
                return@withContext GitHubActionsResult.NotConfigured(
                    "Репозиторий должен иметь формат owner/name",
                )
            }
            if (request.workflow.isBlank() || request.ref.isBlank()) {
                return@withContext GitHubActionsResult.NotConfigured(
                    "Нужны workflow и ref",
                )
            }

            val workflow = URLEncoder.encode(request.workflow.trim(), Charsets.UTF_8.name())
            val endpoint =
                "https://api.github.com/repos/$repository/actions/workflows/$workflow/dispatches"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Authorization", "Bearer ${request.token}")
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                val body = JSONObject()
                    .put("ref", request.ref.trim())
                    .put(
                        "inputs",
                        JSONObject().apply {
                            request.inputs.forEach { (key, value) -> put(key, value) }
                        },
                    )
                    .toString()

                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }

                return@withContext when (val status = connection.responseCode) {
                    204 -> GitHubActionsResult.Dispatched
                    else -> {
                        val details = connection.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?.take(1_600)
                            .orEmpty()
                        GitHubActionsResult.Failed(
                            "GitHub Actions HTTP $status" +
                                if (details.isBlank()) "" else ": $details",
                        )
                    }
                }
            } catch (error: Exception) {
                GitHubActionsResult.Failed(
                    error.message ?: "Не удалось запустить workflow",
                )
            } finally {
                connection.disconnect()
            }
        }
}
