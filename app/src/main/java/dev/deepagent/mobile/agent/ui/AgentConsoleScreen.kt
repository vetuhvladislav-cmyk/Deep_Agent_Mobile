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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deepagent.mobile.agent.core.AgentCore
import dev.deepagent.mobile.agent.model.AgentEvent
import dev.deepagent.mobile.agent.model.AgentEventKind
import dev.deepagent.mobile.agent.model.AgentRequest
import dev.deepagent.mobile.agent.model.AgentSessionStatus
import dev.deepagent.mobile.agent.model.ExecutionTarget
import dev.deepagent.mobile.agent.model.ImageAttachment
import dev.deepagent.mobile.agent.model.PermissionMode
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = remember { AgentCore(context) }
    val state by core.state.collectAsState()
    val events by core.events.collectAsState()

    var task by remember {
        mutableStateOf(
            "Проверь готовность Agent Core и составь краткий план работы с проектом.",
        )
    }
    var target by remember { mutableStateOf(ExecutionTarget.AUTO) }
    var permission by remember { mutableStateOf(PermissionMode.READ_ONLY) }
    var deepSeekKey by remember { mutableStateOf("") }
    var deepSeekBaseUrl by remember { mutableStateOf(DEFAULT_DEEPSEEK_BASE_URL) }
    var model by remember { mutableStateOf(DEFAULT_DEEPSEEK_MODEL) }
    var githubToken by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") }
    var workflow by remember { mutableStateOf("android.yml") }
    var ref by remember { mutableStateOf("main") }
    var showConfig by remember { mutableStateOf(false) }
    var permissionMenuOpen by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ImageAttachment?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(core) {
        onDispose { core.close() }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readImageAttachment(context, uri) }
                .onSuccess {
                    image = it
                    localError = null
                }
                .onFailure {
                    localError = it.message ?: "Не удалось прочитать изображение"
                }
        }
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
                    TextButton(onClick = onBack) { Text("Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = statusLabel(state.status),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(state.status),
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp),
                label = { Text("Задача агенту") },
                placeholder = { Text("Написать код, проанализировать ошибку, собрать APK…") },
                minLines = 4,
                maxLines = 7,
            )

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
                    OutlinedButton(onClick = { permissionMenuOpen = true }) {
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
                OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                    Text(if (image == null) "Добавить изображение" else "Изображение выбрано")
                }
                if (image != null) {
                    TextButton(onClick = { image = null }) { Text("Убрать") }
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
                    enabled = state.status != AgentSessionStatus.RUNNING,
                    onClick = {
                        localError = null
                        scope.launch {
                            core.submit(
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
                                ),
                            )
                        }
                    },
                ) {
                    Text("Запустить")
                }
                OutlinedButton(
                    enabled = state.status == AgentSessionStatus.RUNNING,
                    onClick = { core.cancel() },
                ) {
                    Text("Остановить")
                }
                TextButton(onClick = { core.clearEvents() }) {
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

            Text(
                text = "События Agent Core",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = events,
                    key = { event -> event.createdAt.toString() + event.message.hashCode() },
                ) { event ->
                    AgentEventCard(event)
                }
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
        modifier = Modifier.fillMaxWidth(),
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
}

@Composable
private fun statusColor(status: AgentSessionStatus) = when (status) {
    AgentSessionStatus.FAILED -> MaterialTheme.colorScheme.error
    AgentSessionStatus.WAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
    AgentSessionStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}
