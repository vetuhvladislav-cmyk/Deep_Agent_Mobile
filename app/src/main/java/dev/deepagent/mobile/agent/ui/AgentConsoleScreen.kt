package dev.deepagent.mobile.agent.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.deepagent.mobile.agent.ui.agentControl
import dev.deepagent.mobile.agent.ui.AgentUiContract
import dev.deepagent.mobile.ui.theme.DeepAgentColors
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
import dev.deepagent.mobile.agent.preset.AgentPreset
import dev.deepagent.mobile.agent.preset.AgentPresetPayload
import dev.deepagent.mobile.agent.preset.AgentPresetStore
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.ImageAnalysisStatus
import dev.deepagent.mobile.agent.model.JournalExportStatus
import dev.deepagent.mobile.agent.model.WorkspaceCatalogStatus
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
import java.io.InputStream

private const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
private const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"
private const val DEFAULT_REPOSITORY = "vetuhvladislav-cmyk/Deep_Agent_Mobile"
private const val DEFAULT_WORKFLOW = "android.yml"
private const val DEFAULT_REF = "codex/p1-a-controlled-write-git-pr"
private const val DEFAULT_PRESET_NAME = "Deep Agent Mobile"

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    var repository by rememberSaveable { mutableStateOf(DEFAULT_REPOSITORY) }
    var workflow by rememberSaveable { mutableStateOf(DEFAULT_WORKFLOW) }
    var ref by rememberSaveable { mutableStateOf(DEFAULT_REF) }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var permissionMenuOpen by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var gitError by remember { mutableStateOf<String?>(null) }
    var patchRecoveryError by remember { mutableStateOf<String?>(null) }
    var actionsError by remember { mutableStateOf<String?>(null) }
    var journalExportMessage by remember { mutableStateOf<String?>(null) }
    var journalExportError by remember { mutableStateOf<String?>(null) }
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
    val imageState by agent.image.collectAsState()
    val workspaceCatalog by agent.workspaceCatalog.collectAsState()
    val credentials by agent.credentials.collectAsState()

    val presetStore = remember(context) {
        AgentPresetStore(context.applicationContext)
    }
    var presetName by rememberSaveable {
        mutableStateOf(DEFAULT_PRESET_NAME)
    }
    var presetMessage by remember { mutableStateOf<String?>(null) }
    var presetError by remember { mutableStateOf<String?>(null) }

    fun currentPreset(): AgentPreset = AgentPreset(
        name = presetName.trim().ifBlank { DEFAULT_PRESET_NAME },
        deepSeekBaseUrl = deepSeekBaseUrl.trim(),
        model = model.trim(),
        repository = repository.trim(),
        workflow = workflow.trim(),
        ref = ref.trim(),
        target = target,
        permission = permission,
    )

    fun applyPreset(payload: AgentPresetPayload) {
        val preset = payload.preset
        presetName = preset.name
        deepSeekBaseUrl = preset.deepSeekBaseUrl
        model = preset.model
        repository = preset.repository
        workflow = preset.workflow
        ref = preset.ref
        target = preset.target
        permission = preset.permission
        deepSeekKey = payload.deepSeekApiKey.orEmpty()
        githubToken = payload.githubToken.orEmpty()
        agent.configureCredentials(
            deepSeekApiKey = payload.deepSeekApiKey,
            githubToken = payload.githubToken,
        )
        showConfig = true
        presetMessage = if (payload.credentialsRestored) {
            "Пресет загружен; ключи восстановлены из защищённого хранилища"
        } else {
            "Пресет загружен; ключи нужно ввести заново"
        }
        presetError = null
    }

    DisposableEffect(agent) {
        onDispose { agent.close() }
    }

    fun persistReadPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { agent.prepareImage(uri.toString()) }
                .onSuccess { result ->
                    if (result.status == ImageAnalysisStatus.READY) {
                        localError = null
                    } else {
                        localError = result.summary ?: "Изображение не прошло проверку"
                    }
                }
                .onFailure {
                    localError = it.message ?: "Не удалось подготовить изображение"
                }
        }
    }

    val journalExportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { agent.exportJournal(uri.toString()) }
                .onSuccess { result ->
                    if (result.status == JournalExportStatus.EXPORTED) {
                        journalExportError = null
                        journalExportMessage = result.summary + " · " + result.bytes + " байт"
                    } else {
                        journalExportMessage = null
                        journalExportError = result.summary
                    }
                }
                .onFailure {
                    journalExportMessage = null
                    journalExportError = it.message ?: "Не удалось экспортировать журнал"
                }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistReadPermission(uri)
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

    val presetExportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val preset = currentPreset()
        val deepKey = deepSeekKey
        val githubKey = githubToken
        presetMessage = null
        presetError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val serialized = presetStore.encode(preset, deepKey, githubKey)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(serialized.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: error("Не удалось открыть файл экспорта пресета")
                }
            }.onSuccess {
                presetMessage = "Пресет экспортирован; ключи записаны только в зашифрованном виде"
            }.onFailure {
                presetError = it.message ?: "Не удалось экспортировать пресет"
            }
        }
    }

    val presetImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistReadPermission(uri)
        presetMessage = null
        presetError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        presetStore.decode(readUtf8Limited(input))
                    } ?: error("Не удалось открыть файл пресета")
                }
            }.onSuccess { imported ->
                applyPreset(imported)
            }.onFailure {
                presetError = it.message ?: "Не удалось импортировать пресет"
            }
        }
    }

    val submitCurrentTask: () -> Unit = {
        localError = null
        scope.launch {
            runCatching {
                require(task.isNotBlank()) { "Задача не может быть пустой" }
                agent.configureCredentials(
                    deepSeekApiKey = deepSeekKey,
                    githubToken = githubToken,
                )
                agent.submit(
                    AgentRequest(
                        task = task,
                        target = target,
                        permission = permission,
                        imageAssetId = imageState.assetId.takeIf {
                            imageState.status == ImageAnalysisStatus.READY
                        },
                        deepSeekBaseUrl = deepSeekBaseUrl,
                        model = model,
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
                ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Назад")
                    }
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
                        text = "Цель: " + (state.target?.shortLabel() ?: target.shortLabel()) +
                            " · разрешение: " + permission.shortLabel(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "DeepSeek: " + if (credentials.deepSeekConfigured) {
                            "ключ в памяти · TTL · " + model
                        } else {
                            "ключ не загружен · офлайн-прототип"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "Токен GitHub: " + if (credentials.githubConfigured) {
                            "в памяти · TTL"
                        } else {
                            "не загружен"
                        } +
                            " · Actions: " + if (
                                repository.isNotBlank() &&
                                workflow.isNotBlank()
                            ) {
                                "конфигурация заполнена"
                            } else {
                                "не настроен"
                            },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                AgentUiContract.EXPORT_JOURNAL,
                                "Экспортировать очищенный журнал",
                            ),
                            onClick = {
                                journalExportMessage = null
                                journalExportError = null
                                journalExportPicker.launch("agent-session-journal.json")
                            },
                        ) {
                            Text("Экспорт журнала")
                        }
                        TextButton(
                            modifier = Modifier.agentControl(
                                AgentUiContract.CLEAR_CREDENTIALS,
                                "Очистить учётные данные из памяти",
                            ),
                            onClick = {
                                agent.clearCredentials()
                                deepSeekKey = ""
                                githubToken = ""
                            },
                        ) {
                            Text("Очистить учётные данные")
                        }
                    }
                    journalExportMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    journalExportError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (state.recoveryRequired) {
                        Text(
                            text = "Требуется восстановление: побочный эффект не повторяется автоматически.",
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
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
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
                            "Рабочая область: " + it.displayName +
                                " · " + it.fileCount + " файлов · " +
                                formatWorkspaceBytes(it.totalBytes)
                        } ?: "Рабочая область не выбрана",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "P0-инструменты чтения работают с импортированной копией приложения.",
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
                                "Импортировать ZIP рабочей области",
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
                                "Импортировать папку рабочей области",
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

            if (
                workspaceCatalog.items.isNotEmpty() ||
                    workspaceCatalog.status != WorkspaceCatalogStatus.IDLE
            ) {
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
                            text = "D2 · Каталог рабочей области · " +
                                workspaceCatalog.status.name,
                            fontWeight = FontWeight.SemiBold,
                        )
                        workspaceCatalog.items.take(12).forEach { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = candidate.displayName + " · " +
                                        candidate.fileCount + " файлов",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                OutlinedButton(
                                    modifier = Modifier.agentControl(
                                        AgentUiContract.workspace(candidate.id),
                                        "Выбрать рабочую область " + candidate.displayName,
                                    ),
                                    enabled = candidate.id != workspaceCatalog.selectedId &&
                                        state.status != AgentSessionStatus.RUNNING &&
                                        pendingApproval == null,
                                    onClick = {
                                        workspaceError = null
                                        scope.launch {
                                            runCatching {
                                                agent.selectWorkspace(candidate.id)
                                            }.onFailure {
                                                workspaceError = it.message
                                                    ?: "Не удалось выбрать рабочую область"
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        if (candidate.id == workspaceCatalog.selectedId) {
                                            "Выбран"
                                        } else {
                                            "Выбрать"
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                AgentUiContract.WORKSPACE_REFRESH,
                                "Обновить каталог рабочей области",
                            ),
                            enabled = state.status != AgentSessionStatus.RUNNING,
                            onClick = {
                                workspaceError = null
                                scope.launch {
                                    runCatching {
                                        agent.refreshWorkspace()
                                    }.onFailure {
                                        workspaceError = it.message
                                            ?: "Не удалось обновить каталог рабочей области"
                                    }
                                }
                            },
                        ) {
                            Text("Обновить каталог")
                        }
                        workspaceCatalog.fingerprint?.let {
                            Text(
                                text = "Отпечаток: " + it.take(16) + "…",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (workspaceCatalog.rules.available) {
                            Text(
                                text = "AGENT_RULES.md подключён как дополнительное ограничение" +
                                    if (workspaceCatalog.rules.truncated) {
                                        " (обрезан)"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        workspaceCatalog.summary?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        workspaceCatalog.entries.take(80).forEach { entry ->
                            Text(
                                text = (if (entry.type == "directory") "▸ " else "· ") +
                                    entry.path +
                                    if (entry.type == "file") {
                                        " · " + formatWorkspaceBytes(entry.sizeBytes)
                                    } else {
                                        ""
                                    },
                                modifier = Modifier.agentControl(
                                    AgentUiContract.workspaceEntry(entry.path),
                                    "Элемент рабочей области " + entry.path,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (workspaceCatalog.entriesTruncated) {
                            Text(
                                text = "Дерево ограничено; используйте инструменты чтения для постраничного просмотра.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        workspaceCatalog.errorCode?.let {
                            Text(
                                text = "Ошибка каталога: " + it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                            text = "Предпросмотр изменения: " + pending.path,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "SHA дерева: " + pending.workspaceFingerprint +
                                " · база: " +
                                (pending.oldSha256 ?: "новый") +
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
                                    "Применить изменение после проверки",
                                ),
                                enabled = pending.canApply,
                                onClick = { agent.approvePendingPatch() },
                            ) {
                                Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Применить изменение")
                            }
                            OutlinedButton(
                                modifier = Modifier.agentControl(
                                    AgentUiContract.REJECT_PATCH,
                                    "Отклонить предпросмотр изменения",
                                ),
                                onClick = { agent.rejectPendingPatch() },
                            ) {
                                Text("Отклонить")
                            }
                        }
                        if (!pending.canApply) {
                            Text(
                                text = "Для применения нужно разрешение LOCAL_WRITE при запуске этой сессии.",
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
                                text = "Контрольная точка изменения / восстановление",
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
                                    text = "SHA дерева после записи: " + it,
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
                                                        ?: "Не удалось выполнить откат"
                                                }
                                        }
                                    },
                                ) {
                                    Text("Откатить изменение")
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
                                text = "Откат требует LOCAL_WRITE и выполняется только " +
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
                        text = "P2-A · Супервизор среды выполнения",
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
                            text = "Последняя проверка готовности: " + it,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (permission < PermissionMode.LOCAL_WRITE) {
                        Text(
                            text = "Для запуска локальной среды выберите permission «Локальная запись».",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.agentControl(
                                "agent.runtime.start",
                                "Запустить внутреннюю среду выполнения",
                            ),
                            enabled = runtimeState.status == RuntimeStatus.EMPTY &&
                                permission >= PermissionMode.LOCAL_WRITE &&
                                state.status != AgentSessionStatus.RUNNING &&
                                pendingApproval == null,
                            onClick = {
                                scope.launch {
                                    agent.startRuntime(permission)
                                }
                            },
                        ) {
                            Text("Запустить среду")
                        }
                        OutlinedButton(
                            modifier = Modifier.agentControl(
                                "agent.runtime.stop",
                                "Остановить внутреннюю среду выполнения",
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
                        text = "Адаптер loopback работает внутри одного APK; Termux, DSH APK и внешняя оболочка не используются.",
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
                            text = "P2-B · Интерактивная команда",
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
                                    "Запустить разрешённую интерактивную команду Git",
                                ),
                                enabled = runtimeState.status == RuntimeStatus.READY &&
                                    interactiveState.status != InteractiveSessionStatus.STARTING &&
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
                                                workspaceId = workspace?.id,
                                                sessionId = state.sessionId,
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text("Статус Git")
                            }
                            OutlinedButton(
                                modifier = Modifier.agentControl(
                                    "agent.interactive.cancel",
                                    "Остановить интерактивную команду",
                                ),
                                enabled = interactiveState.status ==
                                    InteractiveSessionStatus.STARTING ||
                                    interactiveState.status == InteractiveSessionStatus.RUNNING,
                                onClick = { agent.cancelInteractive() },
                            ) {
                                Text("Остановить")
                            }
                        }
                        Text(
                            text = "Разрешены только прямые команды Git status/diff/log; shell, sh -c и произвольные команды заблокированы.",
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
                                " · запуск: " +
                                (actionsState.runNumber?.toString()
                                    ?: actionsState.runId?.toString()
                                    ?: "нет"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        actionsState.headSha?.let {
                            Text(
                                text = "Исходный SHA: " + it,
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
                                        artifact.sizeBytes + " байт" +
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
                                                agent.configureCredentials(
                                                    deepSeekApiKey = deepSeekKey,
                                                    githubToken = githubToken,
                                                )
                                                val result = agent.saveVerifiedArtifact(
                                                    ActionsArtifactRequest(
                                                        token = "",
                                                        repository = repository,
                                                        runId = runId,
                                                        artifactId = artifact.id,
                                                        expectedCommitSha = sourceSha,
                                                        workspaceId = workspace?.id,
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
                                    "Явно запустить GitHub Actions",
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
                                        agent.configureCredentials(
                                            deepSeekApiKey = deepSeekKey,
                                            githubToken = githubToken,
                                        )
                                        agent.runActions(
                                            ActionsRunRequest(
                                                token = "",
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
                            text = "Запуск и повтор выполняются только явной кнопкой и требуют GITHUB_WRITE. " +
                                "Артефакт сохраняется после проверки исходного SHA, контрольной суммы и происхождения.",
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
                            text = "P1-A · Git / ручной PR",
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
                                    " · ветка: " + (gitState.branch ?: "DETACHED"),
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
                            label = { Text("Новая ветка") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = gitStartPoint,
                            onValueChange = { gitStartPoint = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Точка старта") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.agentControl(
                                AgentUiContract.CREATE_BRANCH,
                                "Явно создать ветку",
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
                                            ?: "Не удалось создать ветку"
                                    }
                                }
                            },
                        ) {
                            Text("Создать ветку")
                        }
                        OutlinedTextField(
                            value = commitPaths,
                            onValueChange = { commitPaths = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Пути для коммита через запятую или новую строку") },
                            minLines = 2,
                            maxLines = 4,
                        )
                        OutlinedTextField(
                            value = commitMessage,
                            onValueChange = { commitMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Сообщение коммита") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.agentControl(
                                AgentUiContract.COMMIT,
                                "Явно создать коммит",
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
                                            ?: "Не удалось создать коммит"
                                    }
                                }
                            },
                        ) {
                            Text("Создать коммит выбранных путей")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = gitRemote,
                                onValueChange = { gitRemote = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Удалённый репозиторий") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = pushBranch,
                                onValueChange = { pushBranch = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Ветка для push") },
                                singleLine = true,
                            )
                        }
                        Button(
                            modifier = Modifier.agentControl(
                                AgentUiContract.PUSH,
                                "Отправить ветку через push",
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
                            Text("Отправить")
                        }
                        OutlinedTextField(
                            value = repository,
                            onValueChange = { repository = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Владелец/репозиторий PR") },
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
                                label = { Text("Ветка PR") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = pullRequestBase,
                                onValueChange = { pullRequestBase = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Базовая ветка PR") },
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = pullRequestTitle,
                            onValueChange = { pullRequestTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Заголовок PR") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = pullRequestBody,
                            onValueChange = { pullRequestBody = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Описание PR") },
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
                                            agent.configureCredentials(
                                                deepSeekApiKey = deepSeekKey,
                                                githubToken = githubToken,
                                            )
                                            agent.createPullRequest(
                                                request = GitPullRequestRequest(
                                                    repository = repository,
                                                    head = pullRequestHead,
                                                    base = pullRequestBase,
                                                    title = pullRequestTitle,
                                                    body = pullRequestBody,
                                                    draft = pullRequestDraft,
                                                     expectedHeadSha = gitState.headSha,
                                                     sessionId = state.sessionId,
                                                ),
                                                githubToken = "",
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
                                Text(if (pullRequestDraft) "Черновик: да" else "Черновик: нет")
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
                            text = "Ветка, коммит и push требуют GITHUB_WRITE; PR требует PR_CREATE. " +
                                "Нажатие кнопки является явным подтверждением.",
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
                            "Выбрать разрешение текущей сессии",
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
                    Text(
                        if (imageState.status == ImageAnalysisStatus.IDLE) {
                            "Добавить изображение"
                        } else {
                            "Изображение выбрано"
                        },
                    )
                }
                if (imageState.status != ImageAnalysisStatus.IDLE) {
                    TextButton(
                        onClick = { agent.clearImage() },
                    ) {
                        Text("Убрать")
                    }
                }
            }

            if (imageState.status != ImageAnalysisStatus.IDLE) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "Изображение: " +
                                (imageState.displayName ?: "изображение") +
                                " · " + (imageState.mediaType ?: "неизвестный формат"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Состояние: " + imageState.status.name +
                                " · " + formatImageBytes(imageState.sizeBytes ?: 0L) +
                                " · " + (imageState.width ?: 0) + "×" +
                                (imageState.height ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        imageState.checksum?.let {
                            Text(
                                text = "SHA-256: " + it.take(16) + "…",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            text = "Перед передачей в DeepSeek приложение покажет уведомление; " +
                                "исходные байты и data URL не сохраняются в журнале.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        imageState.summary?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
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
                        label = { Text("Ключ DeepSeek API · только в памяти сессии") },
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
                            label = { Text("Базовый URL") },
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
                        label = { Text("Токен GitHub · только в памяти сессии") },
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
                            label = { Text("владелец/репозиторий") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = workflow,
                            onValueChange = { workflow = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Рабочий процесс") },
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
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название пресета") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            enabled = presetName.isNotBlank(),
                            onClick = {
                                val preset = currentPreset()
                                val deepKey = deepSeekKey
                                val githubKey = githubToken
                                presetMessage = null
                                presetError = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            presetStore.saveLocal(
                                                preset,
                                                deepKey,
                                                githubKey,
                                            )
                                        }
                                    }.onSuccess {
                                        presetMessage =
                                            "Локальный пресет сохранён; ключи зашифрованы"
                                    }.onFailure {
                                        presetError = it.message
                                            ?: "Не удалось сохранить пресет"
                                    }
                                }
                            },
                        ) {
                            Text("Сохранить")
                        }
                        OutlinedButton(
                            onClick = {
                                presetMessage = null
                                presetError = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            presetStore.loadLocal()
                                                ?: error("Локальный пресет не найден")
                                        }
                                    }.onSuccess { imported ->
                                        applyPreset(imported)
                                    }.onFailure {
                                        presetError = it.message
                                            ?: "Не удалось загрузить пресет"
                                    }
                                }
                            },
                        ) {
                            Text("Загрузить")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                presetExportPicker.launch("deep-agent-preset.json")
                            },
                        ) {
                            Text("Экспорт")
                        }
                        TextButton(
                            onClick = {
                                presetImportPicker.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/json",
                                        "text/plain",
                                    ),
                                )
                            },
                        ) {
                            Text("Импорт")
                        }
                    }
                    Text(
                        text = "Пресет хранит DeepSeek/GitHub настройки. Ключи не сохраняются открытым текстом.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    presetMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    presetError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
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
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Остановить")
                }
                TextButton(
                    modifier = Modifier.agentControl(
                        AgentUiContract.CLEAR_EVENTS,
                        "Очистить активные события",
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
                text = event.kind.shortLabel(),
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

private fun AgentEventKind.shortLabel(): String = when (this) {
    AgentEventKind.SESSION -> "Сессия"
    AgentEventKind.PLAN -> "План"
    AgentEventKind.REASONING -> "Рассуждение"
    AgentEventKind.OUTPUT -> "Результат"
    AgentEventKind.TOOL -> "Инструмент"
    AgentEventKind.IMAGE -> "Изображение"
    AgentEventKind.BUILD -> "Сборка"
    AgentEventKind.APPROVAL -> "Одобрение"
    AgentEventKind.ARTIFACT -> "Артефакт"
    AgentEventKind.ERROR -> "Ошибка"
    AgentEventKind.INFO -> "Информация"
}

private fun parseCommitPaths(value: String): List<String> = value
    .split(',', '\n', ';')
    .map { it.trim() }
    .filter { it.isNotBlank() }

private fun formatImageBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " Б"
    if (bytes < 1024L * 1024L) {
        return (bytes / 1024L).toString() + " КиБ"
    }
    return (bytes / (1024L * 1024L)).toString() + " МиБ"
}

private fun formatWorkspaceBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " Б"
    if (bytes < 1024L * 1024L) {
        return (bytes / 1024L).toString() + " КиБ"
    }
    return (bytes / (1024L * 1024L)).toString() + " МиБ"
}

private fun ExecutionTarget.shortLabel(): String = when (this) {
    ExecutionTarget.AUTO -> "Авто"
    ExecutionTarget.LOCAL_LITE -> "Локально"
    ExecutionTarget.REMOTE_ACTIONS -> "Actions"
}

private fun PermissionMode.shortLabel(): String = when (this) {
    PermissionMode.READ_ONLY -> "Только чтение"
    PermissionMode.LOCAL_WRITE -> "Локальная запись"
    PermissionMode.GITHUB_WRITE -> "Запись GitHub"
    PermissionMode.PR_CREATE -> "Создание PR"
    PermissionMode.MERGE_RELEASE -> "Слияние / релиз"
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
    AgentSessionStatus.FAILED,
    AgentSessionStatus.UNKNOWN -> DeepAgentColors.Failed
    AgentSessionStatus.WAITING_APPROVAL -> DeepAgentColors.Restricted
    AgentSessionStatus.COMPLETED -> DeepAgentColors.Active
    AgentSessionStatus.RUNNING -> DeepAgentColors.Cyan
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
