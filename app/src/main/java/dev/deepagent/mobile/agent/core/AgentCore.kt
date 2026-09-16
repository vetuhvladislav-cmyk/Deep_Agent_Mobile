package dev.deepagent.mobile.agent.core

import android.content.Context
import dev.deepagent.mobile.agent.deepseek.DeepSeekImage
import dev.deepagent.mobile.agent.deepseek.DeepSeekRequest
import dev.deepagent.mobile.agent.deepseek.DeepSeekResponsesClient
import dev.deepagent.mobile.agent.deepseek.DeepSeekStreamEvent
import dev.deepagent.mobile.agent.github.GitHubActionsClient
import dev.deepagent.mobile.agent.github.GitHubActionsRequest
import dev.deepagent.mobile.agent.github.GitHubActionsResult
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionState
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.runtime.LocalLiteRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Внутренний оркестратор одного APK.
 *
 * Сейчас реализован первый вертикальный срез:
 * - маршрутизация AUTO/LOCAL_LITE/REMOTE_ACTIONS;
 * - локальный health probe;
 * - DeepSeek streaming;
 * - запуск GitHub Actions;
 * - единая лента событий для UI.
 *
 * История сообщений и полноценный tool loop подключаются поверх этого
 * контракта следующим этапом, не меняя UI-протокол.
 */
class AgentCore(context: Context) : AgentBridge {

    private val appContext = context.applicationContext
    private val localRunner = LocalLiteRunner(appContext)
    private val deepSeek = DeepSeekResponsesClient()
    private val actions = GitHubActionsClient()
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AgentSessionState())
    override val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    override val events: StateFlow<List<AgentEvent>> = _events.asStateFlow()

    private var activeJob: Job? = null

    override suspend fun submit(request: AgentRequest) {
        activeJob?.cancel()

        val job = coreScope.launch {
            execute(request)
        }
        activeJob = job

        try {
            job.join()
        } finally {
            if (activeJob === job) activeJob = null
        }
    }

    override fun cancel() {
        activeJob?.cancel()
        _state.value = _state.value.copy(
            status = AgentSessionStatus.CANCELLED,
            finishedAt = System.currentTimeMillis(),
        )
        append(
            AgentEventKind.INFO,
            "Сессия остановлена пользователем",
        )
    }

    override fun clearEvents() {
        _events.value = emptyList()
    }

    fun close() {
        activeJob?.cancel()
        coreScope.cancel()
    }

    private suspend fun execute(request: AgentRequest) {
        val task = request.task.trim()
        if (task.isBlank()) {
            fail("Задача не может быть пустой")
            return
        }

        val target = resolveTarget(request)
        val startedAt = System.currentTimeMillis()
        _state.value = AgentSessionState(
            status = AgentSessionStatus.RUNNING,
            target = target,
            task = task,
            startedAt = startedAt,
        )

        append(AgentEventKind.SESSION, "Сессия Agent Core запущена")
        append(
            AgentEventKind.PLAN,
            "Исполнитель: ${target.label()}",
            "permission=${request.permission.name}",
        )

        try {
            when (target) {
                ExecutionTarget.LOCAL_LITE -> executeLocal(request)
                ExecutionTarget.REMOTE_ACTIONS -> executeRemote(request)
                ExecutionTarget.AUTO -> error("AUTO должен быть разрешён до запуска")
            }

            if (_state.value.status == AgentSessionStatus.RUNNING) {
                _state.value = _state.value.copy(
                    status = AgentSessionStatus.COMPLETED,
                    finishedAt = System.currentTimeMillis(),
                )
                append(AgentEventKind.SESSION, "Сессия завершена")
            }
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(
                status = AgentSessionStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
            )
            append(AgentEventKind.INFO, "Сессия отменена")
        } catch (error: Exception) {
            fail(error.message ?: "Неизвестная ошибка Agent Core")
        }
    }

    private suspend fun executeLocal(request: AgentRequest) {
        append(AgentEventKind.TOOL, "Local Lite Runner: health probe")
        val probe = localRunner.probe()

        append(
            AgentEventKind.TOOL,
            if (probe.exitCode == 0) {
                "Local Lite Runner готов (${probe.durationMs} ms)"
            } else {
                "Local Lite Runner завершился с кодом ${probe.exitCode}"
            },
            listOf(probe.stdout, probe.stderr)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .take(2_000),
        )

        runDeepSeek(request)
    }

    private suspend fun executeRemote(request: AgentRequest) {
        runDeepSeek(request)

        if (request.permission < PermissionMode.GITHUB_WRITE) {
            _state.value = _state.value.copy(
                status = AgentSessionStatus.WAITING_APPROVAL,
            )
            append(
                AgentEventKind.INFO,
                "Запуск GitHub Actions ожидает разрешения GITHUB_WRITE",
            )
            return
        }

        append(
            AgentEventKind.BUILD,
            "Запуск удалённой сборки GitHub Actions",
            "${request.repository ?: "repository не задан"} / " +
                "${request.workflow ?: "workflow не задан"} @ ${request.ref}",
        )

        val result = actions.dispatch(
            GitHubActionsRequest(
                token = request.githubToken.orEmpty(),
                repository = request.repository.orEmpty(),
                workflow = request.workflow.orEmpty(),
                ref = request.ref,
                inputs = mapOf("agent_task" to request.task.take(2_000)),
            ),
        )

        when (result) {
            GitHubActionsResult.Dispatched -> {
                append(
                    AgentEventKind.BUILD,
                    "GitHub Actions workflow поставлен в очередь",
                )
            }

            is GitHubActionsResult.NotConfigured -> {
                fail(result.message)
            }

            is GitHubActionsResult.Failed -> {
                fail(result.message)
            }
        }
    }

    private suspend fun runDeepSeek(request: AgentRequest) {
        val apiKey = request.deepSeekApiKey?.trim().orEmpty()
        if (apiKey.isBlank()) {
            append(
                AgentEventKind.OUTPUT,
                "Offline prototype: DeepSeek API key не задан",
                "Локальный контракт и маршрутизация проверены; для реального ответа " +
                    "укажите ключ DeepSeek в Agent Console.",
            )
            return
        }

        append(
            AgentEventKind.SESSION,
            "DeepSeek ${request.model} streaming запущен",
        )

        deepSeek.stream(
            DeepSeekRequest(
                apiKey = apiKey,
                baseUrl = request.deepSeekBaseUrl,
                model = request.model,
                task = request.task,
                image = request.image?.let {
                    DeepSeekImage(it.dataUrl, it.detail)
                },
            ),
        ) { event ->
            when (event) {
                is DeepSeekStreamEvent.ReasoningDelta -> {
                    append(AgentEventKind.REASONING, event.text)
                }

                is DeepSeekStreamEvent.OutputDelta -> {
                    append(AgentEventKind.OUTPUT, event.text)
                }

                is DeepSeekStreamEvent.ToolArgumentsDelta -> {
                    append(AgentEventKind.TOOL, "Получены аргументы tool call", event.text)
                }

                is DeepSeekStreamEvent.Completed -> {
                    append(AgentEventKind.SESSION, "DeepSeek response завершён")
                }

                is DeepSeekStreamEvent.Failed -> {
                    fail(event.message)
                }
            }
        }
    }

    private fun resolveTarget(request: AgentRequest): ExecutionTarget {
        if (request.target != ExecutionTarget.AUTO) return request.target

        val remoteHint = Regex(
            "(?i)\\b(apk|aab|gradle|android sdk|ndk|cmake|compile|build|сборк)\\b",
        ).containsMatchIn(request.task)

        return if (remoteHint) ExecutionTarget.REMOTE_ACTIONS else ExecutionTarget.LOCAL_LITE
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            status = AgentSessionStatus.FAILED,
            lastError = message,
            finishedAt = System.currentTimeMillis(),
        )
        append(AgentEventKind.ERROR, message)
    }

    private fun append(kind: AgentEventKind, message: String, detail: String? = null) {
        val next = _events.value + AgentEvent(kind, message, detail)
        _events.value = next.takeLast(MAX_EVENTS)
    }

    private fun ExecutionTarget.label(): String = when (this) {
        ExecutionTarget.AUTO -> "AUTO"
        ExecutionTarget.LOCAL_LITE -> "LOCAL_LITE"
        ExecutionTarget.REMOTE_ACTIONS -> "REMOTE_ACTIONS"
    }

    private companion object {
        const val MAX_EVENTS = 500
    }
}
