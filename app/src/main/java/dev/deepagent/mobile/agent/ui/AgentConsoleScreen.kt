package dev.deepagent.mobile.agent.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.deepagent.mobile.agent.ui.agentControl
import dev.deepagent.mobile.agent.ui.AgentUiContract
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepagent.mobile.agent.model.ActionsArtifactRequest
import dev.deepagent.mobile.agent.model.ActionsArtifactSaveStatus
import dev.deepagent.mobile.agent.model.ActionsOperationStatus
import dev.deepagent.mobile.agent.model.ActionsRunRequest
import dev.deepagent.mobile.agent.git.GitBranchRequest
import dev.deepagent.mobile.agent.git.GitCommitRequest
import dev.deepagent.mobile.agent.git.GitPullRequestRequest
import dev.deepagent.mobile.agent.git.GitPushRequest
import dev.deepagent.mobile.agent.protocol.AgentBridge
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.ImageAttachment
import dev.deepagent.mobile.agent.model.PermissionMode
import dev.deepagent.mobile.agent.model.PatchRecoveryStatus
import dev.deepagent.mobile.agent.model.PatchRollbackStatus
import dev.deepagent.mobile.agent.model.RuntimeStatus
import dev.deepagent.mobile.agent.model.InteractiveCommandRequest
import dev.deepagent.mobile.agent.model.InteractiveSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
private const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"

/**
 * Нативная Agent Console внутри единственного APK.
 *
 * Экран intentionally не зависит от внешней web-оболочки: он проверяет собственный
 * AgentBridge v1 и даёт первый рабочий путь local/remote + DeepSeek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConsoleScreen(
    agent: AgentBridge,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by agent.state.collectAsState()
    val events by agent.events.collectAsState()

    var task by rememberSaveable {
        mutableStateOf(
            state.task
                ?: "Проверь готовность Agent Core и составь краткий план работы с проектом.",
        )
    }
    var target by rememberSaveable {
        mutableStateOf(state.target ?: ExecutionTarget.AUTO)
    }
    var permission by rememberSaveable { mutableStateOf(PermissionMode.READ_ONLY) }
    // Tokens intentionally stay out of saved instance state and remain memory-only.
    var deepSeekKey by remember { mutableStateOf("") }
    var deepSeekBaseUrl by rememberSaveable {
        mutableStateOf(DEFAULT_DEEPSEEK_BASE_URL)
    }
    var model by rememberSaveable { mutableStateOf(DEFAULT_DEEPSEEK_MODEL) }
    var githubToken by remember { mutableStateOf("") }
    var repository by rememberSaveable { mutableStateOf("") }
    var workflow by rememberSaveable { mutableStateOf("android.yml") }
    var ref by rememberSaveable { mutableStateOf("main") }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var permissionMenuOpen by remember { mutableStateOf(false) }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<ImageAttachment?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var gitError by remember { mutableStateOf<String?>(null) }
    var patchRecoveryError by remember { mutableStateOf<String?>(null) }
    var actionsError by remember { mutableStateOf<String?>(null) }
    var gitBranch by rememberSaveable { mutableStateOf("agent/task") }
    var gitStartPoint by rememberSaveable { mutableStateOf("HEAD") }
    var commitPaths by rememberSaveable { mutableStateOf("") }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var gitRemote by rememberSaveable { mutableStateOf("origin") }
    var pushBranch by rememberSaveable { mutableStateOf("agent/task") }
    var pullRequestHead by rememberSaveable { mutableStateOf("agent/task") }
    var pullRequestBase by rememberSaveable { mutableStateOf("main") }
    var pullRequestTitle by rememberSaveable { mutableStateOf("") }
    var pullRequestBody by rememberSaveable { mutableStateOf("") }
    var pullRequestDraft by rememberSaveable { mutableStateOf(true) }
    val workspace by agent.workspace.collectAsState()
    val pendingApproval by agent.pendingApproval.collectAsState()
    val gitState by agent.git.collectAsState()
    val patchRecovery by agent.patchRecovery.collectAsState()
    val actionsState by agent.actions.collectAsState()
    val runtimeState by agent.runtime.collectAsState()
    val interactiveState by agent.interactive.collectAsState()

    LaunchedEffect(imageUri) {
        val persistedUri = imageUri ?: return@LaunchedEffect
        runCatching {
            readImageAttachment(context, Uri.parse(persistedUri))
        }.onSuccess {
            image = it
            localError = null
        }.onFailure {
            image = null
            imageUri = null
            localError = it.message ?: "Не удалось восстановить изображение"
        }
    }

    DisposableEffect(agent) {
        onDispose { agent.close() }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readImageAttachment(context, uri) }
                .onSuccess {
                    imageUri = uri.toString()
                    image = it
                    localError = null
                }
                .onFailure {
                    localError = it.message ?: "Не удалось прочитать изображение"
                }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                agent.importWorkspace(uri.toString())
            }.onSuccess {
                workspaceError = null
            }.onFailure {
                workspaceError = it.message ?: "Не удалось импортировать ZIP"
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                agent.importWorkspace(uri.toString())
            }.onSuccess {
                workspaceError = null
            }.onFailure {
                workspaceError = it.message ?: "Не удалось импортировать папку"
            }
        }
    }

    val submitCurrentTask: () -> Unit = {
        localError = null
        scope.launch {
            runCatching {
                require(task.isNotBlank()) { "Задача не может быть пустой" }
                agent.submit(
                    AgentRequest(
                        task = task,
                        target = target,
                        permission = permission,
                        image = image,
                        deepSeekApiKey = deepSeekKey,
                        deepSeekBaseUrl = deepSeekBaseUrl,
                        model = model,
                        githubToken = githubToken,
                        repository = repository,
                        workflow = workflow,
                        ref = ref,
                        workspaceId = workspace?.id,
                    ),
                )
            }.onFailure { error ->
                if (error !is CancellationException) {
                    localError = error.message ?: "Не удалось запустить Agent Core"
                }
            }
        }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Core")
                        Text(
                            text = "один APK · AgentBridge v1",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(
                    modifier = Modifier.agentControl(
                        AgentUiContract.BACK,
                        "Вернуться назад",
                    ),
                    onClick = onBack,
                ) { Text("Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .agentControl(AgentUiContract.ROOT, "Консоль Agent Core"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
            Text(
                text = statusLabel(state.status),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(state.status),
                fontWeight = FontWeight.SemiBold,
            )

            state.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(state.status),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "Сессия: " + (state.sessionId ?: "новая") +
                            " · событие #" + state.eventCursor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Target: " + (state.target?.shortLabel() ?: target.shortLabel()) +
                            " · permission: " + permission.shortLabel(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "DeepSeek: " + if (deepSeekKey.isBlank()) {
                            "ключ не задан · offline prototype"
                        } else {
                            "ключ задан · " + model
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "GitHub Actions: " + if (
                            githubToken.isNotBlank() &&
                            repository.isNotBlank() &&
                            workflow.isNotBlank()
                        ) {
                            "конфигурация заполнена"
                        } else {
                            "не настроен"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (state.recoveryRequired) {
                        Text(
                            text = "Recovery требуется: side effect не повторяется автоматически.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (state.recoveryRequired || state.status == AgentSessionStatus.UNKNOWN) {
                OutlinedButton(
                    enabled = pendingApproval == null,
                    onClick = submitCurrentTask,
                ) {
                    Text("Новая проверка")
                }
            }

            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .agentControl(
                        AgentUiContract.TASK_INPUT,
                        "Задача агенту",
                    ),
                label = { Text("Задача агенту") },
                placeholder = { Text("Написать код, проанализировать ошибку, собрать APK…") },
                minLines = 4,
                maxLines = 7,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = workspace?.let {
                            "Workspace: " + it.displayName +
                                " · " + it.fileCount + " файлов · " +
                                formatWorkspaceBytes(it.totalBytes)
                        } ?: "Workspace не выбран",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "P0 read-only tools работают только с импортированной app-private копией.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                AgentUiContract.IMPORT_ZIP,
                                "Импортировать ZIP workspace",
                            ),
                            onClick = {
                                zipPicker.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                        "application/x-zip-compressed",
                                    ),
                                )
                            },
                        ) {
                            Text("Импорт ZIP")
                        }
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                AgentUiContract.IMPORT_FOLDER,
                                "Импортировать папку workspace",
                            ),
                            onClick = { folderPicker.launch(null) },
                        ) {
                            Text("Импорт папки")
                        }
                    }
                    workspaceError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            pendingApproval?.let { pending ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Patch preview: " + pending.path,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "tree SHA: " + pending.workspaceFingerprint +
                                " · base: " +
                                (pending.oldSha256 ?: "new") +
                                " → " + pending.newSha256,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = pending.unifiedDiff.take(8_000),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                modifier = Modifier.agentControl(
                                    AgentUiContract.APPROVE_PATCH,
                                    "Применить patch после проверки",
                                ),
                                enabled = pending.canApply,
                                onClick = { agent.approvePendingPatch() },
                            ) {
                                Text("Применить patch")
                            }
                            OutlinedButton(
                                modifier = Modifier.agentControl(
                                    AgentUiContract.REJECT_PATCH,
                                    "Отклонить patch preview",
                                ),
                                onClick = { agent.rejectPendingPatch() },
                            ) {
                                Text("Отклонить")
                            }
                        }
                        if (!pending.canApply) {
                            Text(
                                text = "Для применения нужен permission LOCAL_WRITE при запуске этой сессии.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }


            patchRecovery
                ?.takeIf { recovery -> recovery.workspaceId == workspace?.id }
                ?.let { recovery ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Patch checkpoint / recovery",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Операция: " + recovery.operationId.take(8) +
                                    " · файл: " + recovery.path,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = "Состояние: " + recovery.status.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (recovery.status == PatchRecoveryStatus.UNKNOWN) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                },
                            )
                            recovery.workspaceFingerprintAfter?.let {
                                Text(
                                    text = "Post-write tree SHA: " + it,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (recovery.status == PatchRecoveryStatus.APPLIED) {
                                Button(
                                    modifier = Modifier.agentControl(
                                        AgentUiContract.ROLLBACK_PATCH,
                                        "Откатить последний подтверждённый patch",
                                    ),
                                    enabled = state.status != AgentSessionStatus.RUNNING &&
                                        pendingApproval == null,
                                    onClick = {
                                        patchRecoveryError = null
                                        scope.launch {
                                            runCatching { agent.rollbackLastPatch() }
                                                .onSuccess { result ->
                                                    if (
                                                        result.status !=
                                                            PatchRollbackStatus.SUCCEEDED
                                                    ) {
                                                        patchRecoveryError = result.summary
                                                    }
                                                }
                                                .onFailure { error ->
                                                    patchRecoveryError = error.message
                                                        ?: "Не удалось выполнить rollback"
                                                }
                                        }
                                    },
                                ) {
                                    Text("Откатить patch")
                                }
                            }
                            patchRecoveryError?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                text = "Rollback требует LOCAL_WRITE и выполняется только " +
                                    "после повторной проверки fingerprint; автоматического повтора нет.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }




            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "P2-A RuntimeSupervisor",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Состояние: " + runtimeState.status.name +
                            " · " + (runtimeState.version ?: "bundle не выбран"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    runtimeState.summary?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (
                                runtimeState.status == RuntimeStatus.FAILED ||
                                runtimeState.status == RuntimeStatus.ROLLBACK
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    runtimeState.heartbeatAt?.let {
                        Text(
                            text = "Последний readiness probe: " + it,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.agentControl(
                                "agent.runtime.start",
                                "Запустить внутренний runtime",
                            ),
                            enabled = runtimeState.status == RuntimeStatus.EMPTY &&
                                permission >= PermissionMode.LOCAL_WRITE &&
                                state.status != AgentSessionStatus.RUNNING &&
                                pendingApproval == null,
                            onClick = {
                                scope.launch {
                                    agent.startRuntime()
                                }
                            },
                        ) {
                            Text("Запустить runtime")
                        }
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                "agent.runtime.stop",
                                "Остановить внутренний runtime",
                            ),
                            enabled = runtimeState.status != RuntimeStatus.EMPTY &&
                                state.status != AgentSessionStatus.RUNNING,
                            onClick = {
                                scope.launch {
                                    agent.stopRuntime()
                                }
                            },
                        ) {
                            Text("Остановить")
                        }
                    }
                    Text(
                        text = "Loopback adapter работает внутри одного APK; Termux, DSH APK и внешний shell не используются.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }


            if (workspace != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "P2-B Interactive command",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Состояние: " + interactiveState.status.name +
                                " · " + (interactiveState.executable ?: "команда не выбрана"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        interactiveState.summary?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (
                                    interactiveState.status == InteractiveSessionStatus.FAILED ||
                                    interactiveState.status == InteractiveSessionStatus.UNKNOWN
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                        }
                        interactiveState.stdout?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it.take(6_000),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                            )
                        }
                        interactiveState.stderr?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it.take(6_000),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                modifier = Modifier.agentControl(
                                    "agent.interactive.run",
                                    "Запустить разрешённую интерактивную git-команду",
                                ),
                                enabled = runtimeState.status == RuntimeStatus.READY &&
                                    interactiveState.status != InteractiveSessionStatus.RUNNING &&
                                    state.status != AgentSessionStatus.RUNNING &&
                                    pendingApproval == null,
                                onClick = {
                                    scope.launch {
                                        agent.runInteractive(
                                            InteractiveCommandRequest(
                                                executable = "git",
                                                args = listOf(
                                                    "status",
                                                    "--short",
                                                    "--branch",
                                                ),
                                                workspaceId = workspace.id,
                                                sessionId = state.sessionId,
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text("Git status")
                            }
                            OutlinedButton(
                                modifier = Modifier.agentControl(
                                    "agent.interactive.cancel",
                                    "Остановить интерактивную команду",
                                ),
                                enabled = interactiveState.status ==
                                    InteractiveSessionStatus.RUNNING,
                                onClick = { agent.cancelInteractive() },
                            ) {
                                Text("Остановить")
                            }
                        }
                        Text(
                            text = "Сейчас разрешён только прямой read-only git status/diff/log; shell, sh -c и произвольные команды заблокированы.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            if (
                actionsState.status != ActionsOperationStatus.IDLE ||
                target == ExecutionTarget.REMOTE_ACTIONS
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "P1-B GitHub Actions",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Состояние: " + actionsState.status.name +
                                " · run: " +
                                (actionsState.runNumber?.toString()
                                    ?: actionsState.runId?.toString()
                                    ?: "нет"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        actionsState.headSha?.let {
                            Text(
                                text = "Source SHA: " + it,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        actionsState.failedStep?.let {
                            Text(
                                text = "Ошибка шага: " + it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        actionsState.summary?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        actionsState.redactedLogs
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                Text(
                                    text = it.take(6_000),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                )
                            }
                        actionsState.artifacts.forEach { artifact ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = artifact.name + " · " +
                                        artifact.sizeBytes + " bytes" +
                                        if (artifact.verified) " · сохранён" else "",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                val runId = actionsState.runId
                                val sourceSha = actionsState.headSha
                                if (
                                    !artifact.verified &&
                                    actionsState.status == ActionsOperationStatus.SUCCEEDED &&
                                    runId != null &&
                                    sourceSha != null &&
                                    workspace != null
                                ) {
                                    OutlinedButton(
                                        modifier = Modifier.agentControl(
                                            AgentUiContract.DOWNLOAD_ARTIFACT,
                                            "Скачать проверенный APK или AAB",
                                        ),
                                        onClick = {
                                            actionsError = null
                                            scope.launch {
                                                val result = agent.saveVerifiedArtifact(
                                                    ActionsArtifactRequest(
                                                        token = githubToken,
                                                        repository = repository,
                                                        runId = runId,
                                                        artifactId = artifact.id,
                                                        expectedCommitSha = sourceSha,
                                                        workspaceId = workspace.id,
                                                    ),
                                                )
                                                if (
                                                    result.status !=
                                                        ActionsArtifactSaveStatus.VERIFIED_SAVED
                                                ) {
                                                    actionsError = result.summary
                                                }
                                            }
                                        },
                                    ) {
                                        Text("Скачать")
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                modifier = Modifier.agentControl(
                                    AgentUiContract.RUN_ACTIONS,
                                    "Запустить GitHub Actions явно",
                                ),
                                enabled = permission >= PermissionMode.GITHUB_WRITE &&
                                    githubToken.isNotBlank() &&
                                    repository.isNotBlank() &&
                                    workflow.isNotBlank() &&
                                    state.status != AgentSessionStatus.RUNNING &&
                                    pendingApproval == null,
                                onClick = {
                                    actionsError = null
                                    scope.launch {
                                        agent.runActions(
                                            ActionsRunRequest(
                                                token = githubToken,
                                                repository = repository,
                                                workflow = workflow,
                                                ref = ref,
                                                sessionId = actionsState.sessionId
                                                    ?: state.sessionId,
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text("Запустить Actions")
                            }
                        }
                        actionsError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = "Dispatch/retry выполняется только явной кнопкой и требует GITHUB_WRITE. " +
                                "Artifact сохраняется после проверки source SHA, sidecar checksum и provenance.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            if (workspace != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "P1-A Git / ручной PR",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Состояние: " + gitState.status.name +
                                " · " + (gitState.operation ?: "нет операции"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        gitState.summary?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (gitState.status.name == "FAILED" ||
                                    gitState.status.name == "UNKNOWN"
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                        }
                        gitState.headSha?.let {
                            Text(
                                text = "HEAD: " + it +
                                    " · branch: " + (gitState.branch ?: "DETACHED"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        gitState.pullRequestUrl?.let {
                            Text(
                                text = "PR: " + it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                AgentUiContract.INSPECT_GIT,
                                "Проверить состояние Git",
                            ),
                            onClick = {
                                gitError = null
                                scope.launch {
                                    runCatching { agent.inspectGit() }
                                        .onFailure { error ->
                                            gitError = error.message
                                                ?: "Не удалось получить Git status"
                                        }
                                }
                            },
                        ) {
                            Text("Проверить Git")
                        }
                        OutlinedTextField(
                            value = gitBranch,
                            onValueChange = {
                                gitBranch = it
                                if (pullRequestHead == "agent/task") {
                                    pullRequestHead = it
                                }
                                if (pushBranch == "agent/task") {
                                    pushBranch = it
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Новая branch") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = gitStartPoint,
                            onValueChange = { gitStartPoint = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Start point") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.agentControl(
                                AgentUiContract.CREATE_BRANCH,
                                "Создать branch явно",
                            ),
                            onClick = {
                                gitError = null
                                scope.launch {
                                    runCatching {
                                        agent.createGitBranch(
                                            GitBranchRequest(
                                                name = gitBranch,
                                                startPoint = gitStartPoint
                                                    .trim()
                                                    .ifBlank { null },
                                            ),
                                        )
                                    }.onFailure { error ->
                                        gitError = error.message
                                            ?: "Не удалось создать branch"
                                    }
                                }
                            },
                        ) {
                            Text("Создать branch")
                        }
                        OutlinedTextField(
                            value = commitPaths,
                            onValueChange = { commitPaths = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Paths для commit через запятую или новую строку") },
                            minLines = 2,
                            maxLines = 4,
                        )
                        OutlinedTextField(
                            value = commitMessage,
                            onValueChange = { commitMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Commit message") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.agentControl(
                                AgentUiContract.COMMIT,
                                "Создать commit явно",
                            ),
                            onClick = {
                                gitError = null
                                scope.launch {
                                    runCatching {
                                        agent.commitGit(
                                            GitCommitRequest(
                                                paths = parseCommitPaths(commitPaths),
                                                message = commitMessage,
                                            ),
                                        )
                                    }.onFailure { error ->
                                        gitError = error.message
                                            ?: "Не удалось создать commit"
                                    }
                                }
                            },
                        ) {
                            Text("Commit выбранных paths")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = gitRemote,
                                onValueChange = { gitRemote = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Remote") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = pushBranch,
                                onValueChange = { pushBranch = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Push branch") },
                                singleLine = true,
                            )
                        }
                        Button(
                            modifier = Modifier.agentControl(
                                AgentUiContract.PUSH,
                                "Отправить branch через push",
                            ),
                            onClick = {
                                gitError = null
                                scope.launch {
                                    runCatching {
                                        agent.pushGit(
                                            GitPushRequest(
                                                remote = gitRemote,
                                                branch = pushBranch,
                                            ),
                                        )
                                    }.onFailure { error ->
                                        gitError = error.message
                                            ?: "Не удалось выполнить push"
                                    }
                                }
                            },
                        ) {
                            Text("Push")
                        }
                        OutlinedTextField(
                            value = repository,
                            onValueChange = { repository = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("PR repository owner/name") },
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = pullRequestHead,
                                onValueChange = { pullRequestHead = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("PR head") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = pullRequestBase,
                                onValueChange = { pullRequestBase = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("PR base") },
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = pullRequestTitle,
                            onValueChange = { pullRequestTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("PR title") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = pullRequestBody,
                            onValueChange = { pullRequestBody = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("PR body") },
                            minLines = 2,
                            maxLines = 5,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                modifier = Modifier.agentControl(
                                    AgentUiContract.CREATE_PR,
                                    "Создать Pull Request явно",
                                ),
                                onClick = {
                                    gitError = null
                                    scope.launch {
                                        runCatching {
                                            agent.createPullRequest(
                                                request = GitPullRequestRequest(
                                                    repository = repository,
                                                    head = pullRequestHead,
                                                    base = pullRequestBase,
                                                    title = pullRequestTitle,
                                                    body = pullRequestBody,
                                                    draft = pullRequestDraft,
                                                ),
                                                githubToken = githubToken,
                                            )
                                        }.onFailure { error ->
                                            gitError = error.message
                                                ?: "Не удалось создать PR"
                                        }
                                    }
                                },
                            ) {
                                Text("Создать PR")
                            }
                            TextButton(onClick = { pullRequestDraft = !pullRequestDraft }) {
                                Text(if (pullRequestDraft) "Draft: да" else "Draft: нет")
                            }
                        }
                        gitError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = "Branch/commit/push требуют GITHUB_WRITE; PR требует PR_CREATE. " +
                                "Нажатие кнопки является явным approval.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExecutionTarget.entries.forEach { candidate ->
                    FilterChip(
                        selected = target == candidate,
                        onClick = { target = candidate },
                        label = { Text(candidate.shortLabel()) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    OutlinedButton(
                        modifier = Modifier.agentControl(
                            AgentUiContract.PERMISSION_MENU,
                            "Выбрать permission текущей сессии",
                        ),
                        onClick = { permissionMenuOpen = true },
                    ) {
                        Text(permission.shortLabel())
                    }
                    DropdownMenu(
                        expanded = permissionMenuOpen,
                        onDismissRequest = { permissionMenuOpen = false },
                    ) {
                        PermissionMode.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.shortLabel()) },
                                onClick = {
                                    permission = candidate
                                    permissionMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    modifier = Modifier.agentControl(
                        AgentUiContract.IMAGE_PICKER,
                        "Добавить изображение к задаче",
                    ),
                    onClick = { imagePicker.launch("image/*") },
                ) {
                    Text(if (image == null) "Добавить изображение" else "Изображение выбрано")
                }
                if (image != null) {
                    TextButton(
                        onClick = {
                            imageUri = null
                            image = null
                        },
                    ) {
                        Text("Убрать")
                    }
                }
            }

            image?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = "Вложение: ${it.displayName ?: "image"} · ${it.mediaType} · ${it.detail}",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            TextButton(onClick = { showConfig = !showConfig }) {
                Text(if (showConfig) "Скрыть настройки исполнителей" else "Настройки DeepSeek / GitHub")
            }

            if (showConfig) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = deepSeekKey,
                        onValueChange = { deepSeekKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("DeepSeek API key · только память сессии") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = deepSeekBaseUrl,
                            onValueChange = { deepSeekBaseUrl = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Base URL") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Модель") },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = githubToken,
                        onValueChange = { githubToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub token · только память сессии") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = repository,
                            onValueChange = { repository = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("owner/repository") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = workflow,
                            onValueChange = { workflow = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Workflow") },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = ref,
                        onValueChange = { ref = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ветка / ref") },
                        singleLine = true,
                    )
                }
            }

            localError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.agentControl(
                        AgentUiContract.SUBMIT,
                        "Запустить задачу Agent Core",
                    ),
                    enabled = state.status != AgentSessionStatus.RUNNING && pendingApproval == null,
                    onClick = submitCurrentTask,
                ) {
                    Text("Запустить")
                }
                OutlinedButton(
                    modifier = Modifier.agentControl(
                        AgentUiContract.CANCEL,
                        "Остановить текущую сессию",
                    ),
                    enabled = state.status == AgentSessionStatus.RUNNING,
                    onClick = { agent.cancel() },
                ) {
                    Text("Остановить")
                }
                TextButton(
                    modifier = Modifier.agentControl(
                        AgentUiContract.CLEAR_EVENTS,
                        "Очистить live-события",
                    ),
                    onClick = { agent.clearEvents() },
                ) {
                    Text("Очистить")
                }
                if (state.status == AgentSessionStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            HorizontalDivider()
                }
            }

            item {
                Text(
                    text = "События Agent Core",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(
                items = events,
                key = { event -> event.eventId },
            ) { event ->
                AgentEventCard(event)
            }
        }
    }
}

@Composable
private fun AgentEventCard(event: AgentEvent) {
    val isError = event.kind == AgentEventKind.ERROR
    val container = when (event.kind) {
        AgentEventKind.ERROR -> MaterialTheme.colorScheme.errorContainer
        AgentEventKind.REASONING -> MaterialTheme.colorScheme.secondaryContainer
        AgentEventKind.BUILD -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .agentControl(
                AgentUiContract.event(event.sequence),
                "Событие Agent Core " + event.sequence,
            ),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = event.kind.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodySmall,
            )
            event.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    }
}

private suspend fun readImageAttachment(
    context: Context,
    uri: Uri,
): ImageAttachment = withContext(Dispatchers.IO) {
    val mediaType = context.contentResolver.getType(uri)
        ?.takeIf { it.startsWith("image/") }
        ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Не удалось открыть изображение")
    require(bytes.isNotEmpty()) { "Изображение пустое" }
    require(bytes.size <= 32 * 1024 * 1024) {
        "Изображение больше лимита inline input (32 MiB)"
    }

    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
    ImageAttachment(
        dataUrl = "data:$mediaType;base64,$encoded",
        mediaType = mediaType,
        displayName = uri.lastPathSegment,
    )
}

private fun parseCommitPaths(value: String): List<String> = value
    .split(',', '\n', ';')
    .map { it.trim() }
    .filter { it.isNotBlank() }

private fun formatWorkspaceBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    if (bytes < 1024L * 1024L) {
        return (bytes / 1024L).toString() + " KiB"
    }
    return (bytes / (1024L * 1024L)).toString() + " MiB"
}

private fun ExecutionTarget.shortLabel(): String = when (this) {
    ExecutionTarget.AUTO -> "AUTO"
    ExecutionTarget.LOCAL_LITE -> "LOCAL"
    ExecutionTarget.REMOTE_ACTIONS -> "ACTIONS"
}

private fun PermissionMode.shortLabel(): String = when (this) {
    PermissionMode.READ_ONLY -> "Только чтение"
    PermissionMode.LOCAL_WRITE -> "Локальная запись"
    PermissionMode.GITHUB_WRITE -> "GitHub запись"
    PermissionMode.PR_CREATE -> "Создание PR"
    PermissionMode.MERGE_RELEASE -> "Merge / release"
}

private fun statusLabel(status: AgentSessionStatus): String = when (status) {
    AgentSessionStatus.IDLE -> "Готов к запуску"
    AgentSessionStatus.RUNNING -> "Выполняется"
    AgentSessionStatus.WAITING_APPROVAL -> "Ожидает разрешения"
    AgentSessionStatus.COMPLETED -> "Завершено"
    AgentSessionStatus.FAILED -> "Ошибка"
    AgentSessionStatus.CANCELLED -> "Остановлено"
    AgentSessionStatus.UNKNOWN -> "Нужно повторно проверить"
}

@Composable
private fun statusColor(status: AgentSessionStatus) = when (status) {
    AgentSessionStatus.FAILED -> MaterialTheme.colorScheme.error
    AgentSessionStatus.UNKNOWN -> MaterialTheme.colorScheme.error
    AgentSessionStatus.WAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
    AgentSessionStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}
